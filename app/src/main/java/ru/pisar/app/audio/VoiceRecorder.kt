package ru.pisar.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.io.File
import kotlin.math.abs

class VoiceRecorder(
    private val outputFile: File,
    private val sampleRate: Int = 16000,
) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var writer: WavWriter? = null
    private var running = false

    @Volatile
    var level: Int = 0
        private set

    @Volatile
    var failure: String? = null
        private set

    @Volatile
    var startedAt: Long = 0L
        private set

    val isRecording: Boolean get() = running

    fun start(): Boolean {
        if (running) return true
        outputFile.parentFile?.mkdirs()

        val minBuffer = try {
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        } catch (error: Exception) {
            -1
        }
        if (minBuffer <= 0) {
            failure = "микрофон недоступен"
            return false
        }

        val bufferSize = maxOf(minBuffer * 4, 16384)
        val recorder = openRecorder(bufferSize)
        if (recorder == null) {
            failure = "микрофон занят другим приложением"
            return false
        }

        val wav = WavWriter(outputFile, sampleRate, 1)
        writer = wav
        record = recorder
        received = 0

        return try {
            recorder.startRecording()
            running = true
            startedAt = System.currentTimeMillis()
            thread = Thread({ pump(recorder, wav) }, "pisar-recorder").also { it.start() }
            true
        } catch (error: Exception) {
            failure = error.message
            recorder.release()
            record = null
            wav.close()
            writer = null
            false
        }
    }

    private var received: Long = 0L

    private fun pump(recorder: AudioRecord, wav: WavWriter) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffer = ShortArray(3200)
        try {
            while (running) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    wav.write(buffer, read)
                    received += read
                    var peak = 0
                    for (index in 0 until read) {
                        val value = abs(buffer[index].toInt())
                        if (value > peak) peak = value
                    }
                    level = (peak * 100 / 32768).coerceIn(0, 100)
                } else if (read < 0) {
                    break
                }
            }
        } catch (error: Exception) {
            failure = error.message
        }
    }

    private fun openRecorder(bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (source in sources) {
            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            } catch (_: Exception) {
            }
            if (recorder != null && recorder.state == AudioRecord.STATE_INITIALIZED) return recorder
            recorder?.release()
        }
        return null
    }

    fun stop(): Long {
        if (!running) return 0L
        running = false
        try {
            thread?.join(1500)
        } catch (_: InterruptedException) {
        }
        thread = null

        val recorder = record
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        record = null

        writer?.close()
        writer = null
        level = 0

        return if (outputFile.exists()) Wav.durationMs(outputFile) else 0L
    }

    fun cancel() {
        stop()
        outputFile.delete()
    }
}
