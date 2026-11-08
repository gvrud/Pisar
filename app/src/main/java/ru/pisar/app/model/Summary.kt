package ru.pisar.app.model

import org.json.JSONArray
import org.json.JSONObject

data class Summary(
    val shortText: String,
    val bullets: List<String>,
    val keywords: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("short", shortText)
        put("bullets", JSONArray(bullets))
        put("keywords", JSONArray(keywords))
    }

    companion object {
        fun fromJson(json: JSONObject?): Summary? {
            if (json == null) return null
            return Summary(
                shortText = json.optString("short"),
                bullets = json.optJSONArray("bullets").toStringList(),
                keywords = json.optJSONArray("keywords").toStringList(),
            )
        }

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            val result = ArrayList<String>(length())
            for (i in 0 until length()) result.add(optString(i))
            return result
        }
    }
}
