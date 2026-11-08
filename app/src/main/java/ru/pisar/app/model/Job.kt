package ru.pisar.app.model

import org.json.JSONObject

data class Job(
    val id: String,
    val title: String,
    val createdAt: Long,
    val sourceUri: String? = null,
    val audioPath: String? = null,
    val durationMs: Long = 0L,
    val status: Status = Status.QUEUED,
    val progress: Int = 0,
    val stage: String = "",
    val text: String? = null,
    val summary: Summary? = null,
    val error: String? = null,
) {

    enum class Status {
        QUEUED,
        PREPARING,
        DECODING,
        TRANSCRIBING,
        SUMMARIZING,
        DONE,
        ERROR,
        CANCELED;

        val isFinished: Boolean
            get() = this == DONE || this == ERROR || this == CANCELED
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("createdAt", createdAt)
        put("sourceUri", sourceUri ?: JSONObject.NULL)
        put("audioPath", audioPath ?: JSONObject.NULL)
        put("durationMs", durationMs)
        put("status", status.name)
        put("progress", progress)
        put("stage", stage)
        put("text", text ?: JSONObject.NULL)
        put("summary", summary?.toJson() ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): Job = Job(
            id = json.getString("id"),
            title = json.optString("title", "Запись"),
            createdAt = json.optLong("createdAt", 0L),
            sourceUri = json.optStringOrNull("sourceUri"),
            audioPath = json.optStringOrNull("audioPath"),
            durationMs = json.optLong("durationMs", 0L),
            status = runCatching { Status.valueOf(json.optString("status", "QUEUED")) }
                .getOrDefault(Status.QUEUED),
            progress = json.optInt("progress", 0),
            stage = json.optString("stage", ""),
            text = json.optStringOrNull("text"),
            summary = Summary.fromJson(json.optJSONObject("summary")),
            error = json.optStringOrNull("error"),
        )

        private fun JSONObject.optStringOrNull(key: String): String? {
            if (!has(key) || isNull(key)) return null
            return optString(key).takeIf { it.isNotEmpty() }
        }
    }
}
