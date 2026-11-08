package ru.pisar.app.export

import ru.pisar.app.nlp.SummaryResult
import java.io.File

object TxtWriter {

    fun write(
        file: File,
        title: String,
        meta: String,
        summary: SummaryResult?,
        transcript: String,
    ) {
        file.parentFile?.mkdirs()
        val text = buildString {
            appendLine(title)
            if (meta.isNotBlank()) appendLine(meta)
            appendLine()

            if (summary != null) {
                appendLine("КРАТКОЕ СОДЕРЖАНИЕ")
                summary.bullets.forEach { bullet -> appendLine("• $bullet") }
                if (summary.keywords.isNotEmpty()) {
                    appendLine()
                    appendLine("Ключевые слова: " + summary.keywords.joinToString(", "))
                }
                appendLine()
            }

            appendLine("ТРАНСКРИПЦИЯ")
            appendLine()
            val body = transcript.trim()
            appendLine(body.ifEmpty { "—" })
            appendLine()
            appendLine("—")
            appendLine("Расшифровано офлайн в приложении «Писарь»")
        }
        file.writeText(text)
    }
}
