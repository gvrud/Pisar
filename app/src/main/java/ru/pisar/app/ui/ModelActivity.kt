package ru.pisar.app.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.pisar.app.R
import ru.pisar.app.asr.ModelRepository
import ru.pisar.app.databinding.ActivityModelBinding
import ru.pisar.app.util.Format

class ModelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelBinding

    private val pickArchive = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnImportModel.setOnClickListener {
            runCatching {
                pickArchive.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            }.onFailure { pickArchive.launch(arrayOf("*/*")) }
        }
        binding.btnDeleteModel.setOnClickListener { confirmDelete() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val models = ModelRepository.list(this)
        val active = ModelRepository.active(this)

        binding.modelList.removeAllViews()
        models.forEach { entry ->
            val radio = MaterialRadioButton(this).apply {
                text = getString(R.string.model_row, entry.id, Format.bytes(entry.sizeBytes))
                isChecked = entry.id == active?.id
                textSize = 15f
                setPadding(0, 14, 0, 14)
                setOnClickListener {
                    ModelRepository.setActive(this@ModelActivity, entry.id)
                    refresh()
                }
            }
            binding.modelList.addView(radio)
        }

        binding.modelStatus.text = if (active != null) {
            getString(R.string.model_installed, active.id)
        } else {
            getString(R.string.model_missing)
        }
        binding.modelSize.text = if (active != null) {
            getString(R.string.model_size, Format.bytes(active.sizeBytes))
        } else {
            ""
        }
        binding.modelHint.visibility = if (models.isEmpty()) View.VISIBLE else View.GONE
        binding.btnDeleteModel.visibility = if (active != null) View.VISIBLE else View.GONE
    }

    private fun importModel(uri: Uri) {
        binding.progress.visibility = View.VISIBLE
        binding.btnImportModel.isEnabled = false

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ModelRepository.importZip(this@ModelActivity, uri)
                }
                Toast.makeText(this@ModelActivity, R.string.model_imported, Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                Toast.makeText(
                    this@ModelActivity,
                    getString(R.string.model_import_failed, error.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                binding.progress.visibility = View.GONE
                binding.btnImportModel.isEnabled = true
                refresh()
            }
        }
    }

    private fun confirmDelete() {
        val active = ModelRepository.active(this) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_delete)
            .setMessage(getString(R.string.model_delete_confirm, active.id))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                ModelRepository.delete(this, active.id)
                Toast.makeText(this, R.string.model_deleted, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
