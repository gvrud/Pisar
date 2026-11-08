package ru.pisar.app.export

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import ru.pisar.app.R
import ru.pisar.app.model.Job
import ru.pisar.app.model.Summary
import ru.pisar.app.nlp.SummaryResult
import ru.pisar.app.util.Format
import java.io.File

object Exporter {

    const val MIME_TXT = "text/plain"
    const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    fun buildTxt(context: Context, job: Job): File {
        val file = File(exportDir(context), "${safeName(job)}.txt")
        TxtWriter.write(file, job.title, meta(job), summaryOf(job), job.text.orEmpty())
        return file
    }

    fun buildDocx(context: Context, job: Job): File {
        val file = File(exportDir(context), "${safeName(job)}.docx")
        DocxWriter.write(file, job.title, meta(job), summaryOf(job), job.text.orEmpty())
        return file
    }

    fun share(context: Context, file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.share_chooser))
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private fun exportDir(context: Context): File {
        val dir = File(context.cacheDir, "exports")
        dir.mkdirs()
        val cutoff = System.currentTimeMillis() - 12 * 60 * 60 * 1000L
        dir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
        return dir
    }

    private fun safeName(job: Job): String {
        val base = job.title
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(60)
            .ifEmpty { "расшифровка" }
        return "$base ${Format.dateTime(job.createdAt).replace(Regex("[^0-9а-яА-Яa-zA-Z ]"), "")}"
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private fun meta(job: Job): String = buildString {
        append(Format.dateTime(job.createdAt))
        if (job.durationMs > 0) append(" · ").append(Format.duration(job.durationMs))
        job.text?.let { text ->
            val words = Format.wordCount(text)
            if (words > 0) append(" · ").append("$words слов")
        }
    }

    fun summaryOf(job: Job): SummaryResult? {
        val summary: Summary = job.summary ?: return null
        if (summary.bullets.isEmpty() && summary.shortText.isBlank()) return null
        return SummaryResult(summary.shortText, summary.bullets, summary.keywords)
    }
}
