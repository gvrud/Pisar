package ru.pisar.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Format {

    private val dateFormat = SimpleDateFormat("d MMMM, HH:mm", Locale("ru", "RU"))

    fun duration(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    fun clock(ms: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    fun dateTime(millis: Long): String = dateFormat.format(Date(millis))

    fun bytes(count: Long): String {
        if (count < 1024) return "$count Б"
        val kb = count / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f КБ", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f МБ", mb)
        return String.format(Locale.US, "%.2f ГБ", mb / 1024.0)
    }

    fun wordCount(text: String): Int =
        text.split(Regex("[^\\p{L}\\p{N}]+")).count { it.isNotBlank() }
}
