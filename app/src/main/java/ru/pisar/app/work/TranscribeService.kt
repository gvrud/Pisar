package ru.pisar.app.work

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.vosk.Model
import ru.pisar.app.R
import ru.pisar.app.asr.ModelMissingException
import ru.pisar.app.asr.ModelRepository
import ru.pisar.app.asr.Transcriber
import ru.pisar.app.asr.TranscriptionCancelledException
import ru.pisar.app.audio.AudioImporter
import ru.pisar.app.audio.UnsupportedAudioException
import ru.pisar.app.audio.Wav
import ru.pisar.app.model.Job
import ru.pisar.app.model.JobStore
import ru.pisar.app.nlp.TextAnalyzer
import ru.pisar.app.util.Notifications
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TranscribeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    private var model: Model? = null
    private var activeJobId: String? = null
    private var lastNotificationAt = 0L
    private val pending = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for (jobId in queue) {
                try {
                    process(jobId)
                } catch (_: TranscriptionCancelledException) {
                    markCancelled(jobId)
                } catch (token: CancellationException) {
                    markCancelled(jobId)
                    throw token
                } catch (error: Throwable) {
                    fail(jobId, error)
                } finally {
                    activeJobId = null
                }
                if (pending.decrementAndGet() <= 0) {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENQUEUE -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                if (jobId.isNullOrEmpty()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startForegroundFor(jobId)
                pending.incrementAndGet()
                queue.trySend(jobId)
            }

            ACTION_CANCEL -> {
                intent.getStringExtra(EXTRA_JOB_ID)?.let { cancelJob(it) }
                if (pending.get() <= 0 && activeJobId == null) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        queue.close()
        scope.cancel()
        runCatching { model?.close() }
        model = null
        super.onDestroy()
    }

    private fun startForegroundFor(jobId: String) {
        val job = JobStore.find(jobId)
        val notification = if (job != null) {
            Notifications.progress(this, job)
        } else {
            Notifications.starting(this)
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifications.PROGRESS_ID, notification, type)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private suspend fun process(jobId: String) {
        var job = JobStore.find(jobId) ?: return
        if (cancelled.contains(jobId)) {
            markCancelled(jobId)
            return
        }
        activeJobId = jobId

        update(jobId, Job.Status.PREPARING, 2, getString(R.string.status_preparing))

        val modelDir = ModelRepository.ensure(this) { progress ->
            update(
                jobId,
                Job.Status.PREPARING,
                2 + (progress * 4).toInt(),
                getString(R.string.status_preparing),
            )
        }

        update(jobId, Job.Status.DECODING, 8, getString(R.string.status_decoding))
        val wav = prepareAudio(job, jobId) { progress ->
            update(
                jobId,
                Job.Status.DECODING,
                8 + (progress * 30).toInt(),
                getString(R.string.status_decoding),
            )
        }

        val duration = Wav.durationMs(wav)
        JobStore.update(jobId) { current ->
            current.copy(
                audioPath = wav.absolutePath,
                durationMs = if (duration > 0) duration else current.durationMs,
            )
        }
        job = JobStore.find(jobId) ?: return

        if (cancelled.contains(jobId)) {
            markCancelled(jobId)
            return
        }

        val loadedModel = obtainModel(modelDir)
        update(jobId, Job.Status.TRANSCRIBING, 42, getString(R.string.status_transcribing))

        val transcriber = Transcriber(loadedModel)
        val text = transcriber.transcribe(
            wav,
            onProgress = { progress ->
                update(
                    jobId,
                    Job.Status.TRANSCRIBING,
                    42 + (progress * 52).toInt(),
                    getString(R.string.status_transcribing),
                )
            },
            isCancelled = { cancelled.contains(jobId) },
        )

        update(jobId, Job.Status.SUMMARIZING, 96, getString(R.string.status_summarizing))
        val summary = if (text.isNotBlank()) TextAnalyzer.summarize(text) else null

        val imported = job.sourceUri != null
        JobStore.update(jobId) { current ->
            current.copy(
                status = Job.Status.DONE,
                progress = 100,
                stage = "",
                text = text,
                summary = summary?.let {
                    ru.pisar.app.model.Summary(it.shortText, it.bullets, it.keywords)
                },
                error = null,
                audioPath = if (imported) null else wav.absolutePath,
            )
        }

        if (imported) wav.delete()

        val finished = JobStore.find(jobId) ?: return
        NotificationManagerCompat.from(this)
            .let { manager ->
                runCatching { manager.notify(Notifications.DONE_ID, Notifications.done(this, finished)) }
            }
    }

    private fun prepareAudio(job: Job, jobId: String, onProgress: (Float) -> Unit): File {
        val target = File(File(filesDir, "audio"), "decoded-$jobId.wav")
        val existing = job.audioPath?.let { File(it) }
        if (existing != null && existing.exists() && existing.length() > 44) {
            val info = Wav.info(existing)
            if (info != null && info.sampleRate == 16000 && info.channels == 1 &&
                info.bitsPerSample == 16 && info.audioFormat != 3
            ) {
                return existing
            }
            target.parentFile?.mkdirs()
            AudioImporter.prepareFromFile(existing, target, onProgress)
            return target
        }

        val source = job.sourceUri ?: throw IOException(getString(R.string.error_file_missing))
        target.parentFile?.mkdirs()
        AudioImporter.prepareFromUri(this, Uri.parse(source), target, onProgress)
        return target
    }

    private fun obtainModel(dir: File): Model {
        model?.let { return it }
        val loaded = Model(dir.absolutePath)
        model = loaded
        return loaded
    }

    private fun update(jobId: String, status: Job.Status, progress: Int, stage: String) {
        JobStore.update(jobId) { current ->
            current.copy(status = status, progress = progress.coerceIn(0, 100), stage = stage)
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationAt >= 400) {
            lastNotificationAt = now
            val job = JobStore.find(jobId) ?: return
            runCatching {
                NotificationManagerCompat.from(this)
                    .notify(Notifications.PROGRESS_ID, Notifications.progress(this, job))
            }
        }
    }

    private fun markCancelled(jobId: String) {
        JobStore.update(jobId) { current ->
            current.copy(status = Job.Status.CANCELED, progress = 0, stage = "")
        }
    }

    private fun fail(jobId: String, error: Throwable) {
        val message = when (error) {
            is ModelMissingException -> getString(R.string.error_no_model)
            is UnsupportedAudioException -> {
                val detail = error.message?.takeIf { it.isNotBlank() }
                if (detail != null) getString(R.string.error_decode, detail) else getString(R.string.error_unsupported_audio)
            }
            is OutOfMemoryError -> getString(R.string.error_out_of_memory)
            else -> error.message?.takeIf { it.isNotBlank() }
                ?: getString(R.string.error_generic, error.javaClass.simpleName)
        }
        JobStore.update(jobId) { current ->
            current.copy(status = Job.Status.ERROR, progress = 0, stage = "", error = message)
        }
        val job = JobStore.find(jobId) ?: return
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(Notifications.DONE_ID, Notifications.error(this, job))
        }
    }

    private fun cancelJob(jobId: String) {
        cancelled.add(jobId)
        if (activeJobId != jobId) {
            JobStore.update(jobId) { current ->
                current.copy(status = Job.Status.CANCELED, progress = 0, stage = "")
            }
        }
    }

    companion object {
        const val ACTION_ENQUEUE = "ru.pisar.app.action.ENQUEUE"
        const val ACTION_CANCEL = "ru.pisar.app.action.CANCEL"
        const val EXTRA_JOB_ID = "job_id"

        fun enqueue(context: Context, jobId: String) {
            val intent = Intent(context, TranscribeService::class.java).apply {
                action = ACTION_ENQUEUE
                putExtra(EXTRA_JOB_ID, jobId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context, jobId: String) {
            val intent = Intent(context, TranscribeService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_JOB_ID, jobId)
            }
            runCatching { context.startService(intent) }
        }
    }
}
