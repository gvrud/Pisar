package ru.pisar.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.pisar.app.R
import ru.pisar.app.databinding.ActivityJobBinding
import ru.pisar.app.export.Exporter
import ru.pisar.app.model.Job
import ru.pisar.app.model.JobStore
import ru.pisar.app.util.Format
import ru.pisar.app.work.TranscribeService
import java.io.File

class JobActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJobBinding
    private var jobId: String? = null
    private var current: Job? = null
    private var searchQuery: String = ""

    private var player: MediaPlayer? = null
    private var prepared = false
    private var playing = false
    private var playerSourceKey: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val progressTick = object : Runnable {
        override fun run() {
            val active = player ?: return
            if (!prepared) return
            val position = runCatching { active.currentPosition }.getOrDefault(0)
            val duration = runCatching { active.duration }.getOrDefault(0)
            binding.playerProgress.progress = position
            binding.playerTime.text = "${Format.clock(position.toLong())} / ${Format.clock(duration.toLong())}"
            if (playing) handler.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJobBinding.inflate(layoutInflater)
        setContentView(binding.root)

        jobId = intent.getStringExtra(EXTRA_JOB_ID)
        searchQuery = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        if (jobId == null) {
            finish()
            return
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_delete) {
                confirmDelete()
                true
            } else {
                false
            }
        }

        binding.btnShareTxt.setOnClickListener { shareFile(docx = false) }
        binding.btnShareDocx.setOnClickListener { shareFile(docx = true) }
        binding.btnCopy.setOnClickListener { copyTranscript() }

        binding.btnPlay.setOnClickListener { togglePlayback() }
        binding.btnStopPlayback.setOnClickListener { stopPlayback() }
        binding.playerProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && prepared) runCatching { player?.seekTo(progress) }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                JobStore.jobs.collect { jobs ->
                    val job = jobs.firstOrNull { it.id == jobId }
                    if (job == null) {
                        if (!isFinishing) finish()
                    } else {
                        current = job
                        render(job)
                    }
                }
            }
        }
    }

    private fun render(job: Job) {
        binding.title.text = job.title

        binding.meta.text = buildString {
            append(Format.dateTime(job.createdAt))
            if (job.durationMs > 0) append(" · ").append(Format.duration(job.durationMs))
        }

        binding.status.text = JobAdapter.statusLabel(this, job)
        binding.status.setTextColor(
            ContextCompat.getColor(this, JobAdapter.statusColor(job.status)),
        )

        val active = !job.status.isFinished
        binding.progress.visibility = if (active) View.VISIBLE else View.GONE
        binding.progress.progress = job.progress

        val transcript = job.text?.trim().orEmpty()
        binding.transcript.text = when {
            transcript.isNotEmpty() -> Highlight.highlight(
                transcript,
                searchQuery,
                ContextCompat.getColor(this, R.color.highlight),
            )
            job.status == Job.Status.ERROR -> job.error ?: getString(R.string.status_error)
            job.status == Job.Status.DONE -> getString(R.string.job_empty_transcript)
            job.status == Job.Status.CANCELED -> getString(R.string.status_canceled)
            else -> job.stage.ifBlank { getString(R.string.status_queued) }
        }

        val summary = job.summary
        if (summary != null && summary.bullets.isNotEmpty()) {
            binding.summaryCard.visibility = View.VISIBLE
            binding.summaryText.text = summary.bullets.joinToString("\n") { "• $it" }
            binding.keywords.text = if (summary.keywords.isNotEmpty()) {
                getString(R.string.job_keywords) + ": " + summary.keywords.joinToString(", ")
            } else {
                ""
            }
        } else {
            binding.summaryCard.visibility = View.GONE
        }

        binding.stats.text = if (transcript.isNotEmpty()) {
            getString(R.string.stats_words, Format.wordCount(transcript)) + " · " +
                getString(R.string.stats_chars, transcript.length)
        } else {
            ""
        }

        val enabled = job.status == Job.Status.DONE && transcript.isNotEmpty()
        binding.btnShareTxt.isEnabled = enabled
        binding.btnShareDocx.isEnabled = enabled
        binding.btnCopy.isEnabled = enabled

        updatePlayerVisibility(job)
    }

    private fun updatePlayerVisibility(job: Job) {
        val key = playerKey(job)
        binding.playerCard.visibility = if (key != null) View.VISIBLE else View.GONE
        if (playerSourceKey != null && playerSourceKey != key) {
            releasePlayer()
        }
        playerSourceKey = key
    }

    private fun playerKey(job: Job): String? {
        val path = job.audioPath
        if (path != null && File(path).isFile) return "file:$path"
        return job.sourceUri?.let { "uri:$it" }
    }

    private fun togglePlayback() {
        val job = current ?: return
        val active = player
        if (active == null) {
            preparePlayer(job)
            return
        }
        if (playing) {
            runCatching { active.pause() }
            playing = false
        } else if (prepared) {
            runCatching { active.start() }
            playing = true
            handler.post(progressTick)
        }
        updatePlayIcon()
    }

    private fun preparePlayer(job: Job) {
        releasePlayer()
        val mediaPlayer = MediaPlayer()
        try {
            val path = job.audioPath
            if (path != null && File(path).isFile) {
                mediaPlayer.setDataSource(path)
            } else {
                val uri = job.sourceUri?.let { Uri.parse(it) }
                    ?: throw IllegalArgumentException("нет источника")
                mediaPlayer.setDataSource(this, uri)
            }
            mediaPlayer.setOnPreparedListener { mp ->
                prepared = true
                binding.playerProgress.max = mp.duration.coerceAtLeast(1)
                runCatching { mp.start() }
                playing = true
                updatePlayIcon()
                handler.post(progressTick)
            }
            mediaPlayer.setOnCompletionListener {
                playing = false
                prepared = false
                binding.playerProgress.progress = 0
                binding.playerTime.text = "00:00 / 00:00"
                updatePlayIcon()
                handler.removeCallbacks(progressTick)
            }
            mediaPlayer.setOnErrorListener { _, _, _ ->
                playing = false
                prepared = false
                Toast.makeText(this, R.string.player_error, Toast.LENGTH_SHORT).show()
                releasePlayer()
                true
            }
            mediaPlayer.prepareAsync()
            player = mediaPlayer
        } catch (error: Exception) {
            runCatching { mediaPlayer.release() }
            Toast.makeText(this, R.string.player_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayback() {
        releasePlayer()
        binding.playerProgress.progress = 0
        binding.playerTime.text = "00:00 / 00:00"
    }

    private fun releasePlayer() {
        handler.removeCallbacks(progressTick)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        prepared = false
        playing = false
        updatePlayIcon()
    }

    private fun updatePlayIcon() {
        binding.btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun shareFile(docx: Boolean) {
        val job = current ?: return
        if (job.text.isNullOrBlank()) return

        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    if (docx) {
                        Exporter.buildDocx(this@JobActivity, job)
                    } else {
                        Exporter.buildTxt(this@JobActivity, job)
                    }
                }
                Exporter.share(
                    this@JobActivity,
                    file,
                    if (docx) Exporter.MIME_DOCX else Exporter.MIME_TXT,
                )
            } catch (error: Exception) {
                Toast.makeText(
                    this@JobActivity,
                    error.message ?: getString(R.string.status_error),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun copyTranscript() {
        val text = current?.text ?: return
        if (text.isBlank()) return
        val manager = getSystemService(ClipboardManager::class.java) ?: return
        manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.job_transcript), text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(R.string.dialog_delete_text)
            .setPositiveButton(R.string.action_delete) { _, _ -> deleteJob() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deleteJob() {
        val job = current ?: return
        if (!job.status.isFinished) TranscribeService.cancel(this, job.id)
        job.audioPath?.let { path -> runCatching { File(path).delete() } }
        JobStore.remove(job.id)
        finish()
    }

    override fun onStop() {
        if (playing) {
            runCatching { player?.pause() }
            playing = false
            updatePlayIcon()
        }
        super.onStop()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_QUERY = "query"
    }
}
