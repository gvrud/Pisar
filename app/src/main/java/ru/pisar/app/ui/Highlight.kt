package ru.pisar.app.ui

import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan

object Highlight {

    fun normalize(value: String): String = value.lowercase().replace('ё', 'е')

    fun highlight(text: String, query: String, color: Int): CharSequence {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || text.isEmpty()) return text

        val needle = normalize(trimmed)
        val haystack = normalize(text)
        if (!haystack.contains(needle)) return text

        val spannable = SpannableString(text)
        var index = haystack.indexOf(needle)
        var guard = 0
        while (index >= 0 && guard < 500) {
            spannable.setSpan(
                BackgroundColorSpan(color),
                index,
                index + needle.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            guard++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return spannable
    }

    fun snippet(text: String, query: String, radius: Int = 60): String? {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || text.isEmpty()) return null

        val index = normalize(text).indexOf(normalize(trimmed))
        if (index < 0) return null

        val from = (index - radius).coerceAtLeast(0)
        val to = (index + trimmed.length + radius).coerceAtMost(text.length)
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < text.length) "…" else ""
        return prefix + text.substring(from, to).replace('\n', ' ') + suffix
    }

    fun matches(job: ru.pisar.app.model.Job, query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        val needle = normalize(trimmed)
        val haystack = buildString {
            append(job.title)
            append('\n')
            job.text?.let { append(it) }
            append('\n')
            job.summary?.let { summary ->
                summary.shortText.let { append(it) }
                summary.bullets.forEach { append('\n').append(it) }
                append('\n').append(summary.keywords.joinToString(" "))
            }
        }
        return normalize(haystack).contains(needle)
    }
}
