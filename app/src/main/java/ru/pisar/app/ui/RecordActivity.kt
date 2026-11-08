package ru.pisar.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ru.pisar.app.R
import ru.pisar.app.audio.VoiceRecorder
import ru.pisar.app.databinding.ActivityRecordBinding
import ru.pisar.app.model.Job
import ru.pisar.app.model.JobStore
import ru.pisar.app.util.Format
import ru.pisar.app.work.TranscribeService
import java.io.File
import java.util.UUID

class RecordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordBinding
    private var recorder: VoiceRecorder? = null
    private var outputFile: File? = null
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            val active = recorder ?: return
            binding.timer.text = Format.clock(System.currentTimeMillis() - active.startedAt)
            binding.level.progress = active.level
            handler.postDelayed(this, 200)
        }
    }

    private val requestMicrophone =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecording()
            } else {
                Toast.makeText(this, R.string.record_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.fabRecord.setOnClickListener {
            if (recorder?.isRecording == true) finishRecording() else ensurePermission()
        }
        binding.btnFinish.setOnClickListener { finishRecording() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val active = recorder
                if (active != null && active.isRecording) {
                    MaterialAlertDialogBuilder(this@RecordActivity)
                        .setTitle(R.string.dialog_cancel_job_title)
                        .setMessage(R.string.dialog_cancel_job_text)
                        .setPositiveButton(R.string.action_stop) { _, _ ->
                            active.cancel()
                            outputFile?.delete()
                            finish()
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                } else {
                    finish()
                }
            }
        })
    }

    private fun ensurePermission() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            startRecording()
        } else {
            requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        val file = File(File(filesDir, "audio"), "note-${System.currentTimeMillis()}.wav")
        val active = VoiceRecorder(file)
        if (!active.start()) {
            Toast.makeText(
                this,
                getString(R.string.record_error, active.failure ?: "микрофон недоступен"),
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        recorder = active
        outputFile = file

        binding.hint.setText(R.string.record_running)
        binding.fabRecord.setImageResource(R.drawable.ic_stop)
        binding.fabRecord.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.err))
        binding.btnFinish.visibility = View.VISIBLE
        binding.timer.text = "00:00"
        handler.post(ticker)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun finishRecording() {
        val active = recorder ?: return
        val file = outputFile
        val duration = active.stop()
        recorder = null
        handler.removeCallbacks(ticker)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        resetUi()

        if (file == null || duration < 700L) {
            file?.delete()
            Toast.makeText(this, R.string.record_too_short, Toast.LENGTH_SHORT).show()
            return
        }

        val job = Job(
            id = UUID.randomUUID().toString(),
            title = getString(R.string.record_title) + " " + Format.dateTime(System.currentTimeMillis()),
            createdAt = System.currentTimeMillis(),
            audioPath = file.absolutePath,
            durationMs = duration,
            status = Job.Status.QUEUED,
            stage = getString(R.string.status_queued),
        )
        JobStore.add(job)
        TranscribeService.enqueue(this, job.id)

        startActivity(
            Intent(this, JobActivity::class.java)
                .putExtra(JobActivity.EXTRA_JOB_ID, job.id),
        )
        finish()
    }

    private fun resetUi() {
        binding.hint.setText(R.string.record_hint)
        binding.fabRecord.setImageResource(R.drawable.ic_mic)
        binding.fabRecord.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.brand),
        )
        binding.btnFinish.visibility = View.GONE
        binding.level.progress = 0
        binding.timer.text = "00:00"
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        recorder?.let { active ->
            if (active.isRecording) {
                active.cancel()
                outputFile?.delete()
            }
        }
        recorder = null
        super.onDestroy()
    }
}
