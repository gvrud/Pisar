package ru.pisar.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import ru.pisar.app.R
import ru.pisar.app.asr.ModelRepository
import ru.pisar.app.databinding.ActivityMainBinding
import ru.pisar.app.model.Job
import ru.pisar.app.model.JobStore
import ru.pisar.app.work.TranscribeService
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: JobAdapter

    private var allJobs: List<Job> = emptyList()
    private var currentQuery: String = ""

    private val importAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handleImport(uri)
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = JobAdapter { job -> openJob(job.id) }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.btnImport.setOnClickListener { pickFile() }
        binding.btnRecord.setOnClickListener {
            startActivity(Intent(this, RecordActivity::class.java))
        }

        setupSearch()

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_model -> {
                    startActivity(Intent(this, ModelActivity::class.java))
                    true
                }

                R.id.action_help -> {
                    startActivity(Intent(this, HelpActivity::class.java))
                    true
                }

                R.id.action_about -> {
                    showAbout()
                    true
                }

                else -> false
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                JobStore.jobs.collect { jobs ->
                    allJobs = jobs.sortedByDescending { it.createdAt }
                    render()
                }
            }
        }

        requestNotificationPermission()

        if (savedInstanceState == null) {
            handleShareIntent(intent)
            promptForModel()
        }
    }

    private fun setupSearch() {
        val item = binding.toolbar.menu.findItem(R.id.action_search) ?: return
        val view = item.actionView as? SearchView ?: return
        view.queryHint = getString(R.string.search_hint)
        view.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                applyQuery(query.orEmpty())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                applyQuery(newText.orEmpty())
                return true
            }
        })
        view.setOnCloseListener {
            applyQuery("")
            false
        }
    }

    private fun applyQuery(query: String) {
        currentQuery = query.trim()
        render()
    }

    private fun render() {
        val filtered = if (currentQuery.isEmpty()) {
            allJobs
        } else {
            allJobs.filter { job -> Highlight.matches(job, currentQuery) }
        }

        adapter.submitList(filtered.map { job -> JobRow(job, currentQuery) })

        val empty = filtered.isEmpty()
        binding.empty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.list.visibility = if (empty) View.GONE else View.VISIBLE

        if (empty) {
            val searching = currentQuery.isNotEmpty()
            binding.emptyTitle.setText(if (searching) R.string.search_empty else R.string.empty_title)
            binding.emptyText.setText(if (searching) R.string.search_empty_hint else R.string.empty_text)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun pickFile() {
        val types = arrayOf("audio/*", "application/ogg", "application/octet-stream")
        runCatching { importAudio.launch(types) }
            .onFailure { importAudio.launch(arrayOf("*/*")) }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri != null) handleImport(uri)
    }

    private fun handleImport(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val displayName = queryDisplayName(uri) ?: "Аудио"
        val title = displayName.substringBeforeLast('.').ifBlank { displayName }

        val job = Job(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = System.currentTimeMillis(),
            sourceUri = uri.toString(),
            status = Job.Status.QUEUED,
            stage = getString(R.string.status_queued),
        )
        JobStore.add(job)
        TranscribeService.enqueue(this, job.id)
        openJob(job.id)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun openJob(id: String) {
        val intent = Intent(this, JobActivity::class.java)
            .putExtra(JobActivity.EXTRA_JOB_ID, id)
        if (currentQuery.isNotBlank()) {
            intent.putExtra(JobActivity.EXTRA_QUERY, currentQuery)
        }
        startActivity(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun promptForModel() {
        if (ModelRepository.isInstalled(this)) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.model_missing)
            .setMessage(R.string.model_missing_hint)
            .setPositiveButton(R.string.action_import_model) { _, _ ->
                startActivity(Intent(this, ModelActivity::class.java))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, MainActivity::class.java))
        }
    }
}
