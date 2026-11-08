package ru.pisar.app.asr

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import ru.pisar.app.audio.WavReader
import java.io.File

class TranscriptionCancelledException : Exception("Распознавание отменено")

class Transcriber(private val model: Model) {

    data class Word(val text: String, val start: Double, val end: Double)

    fun transcribe(
        wav: File,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): String {
        val reader = WavReader(wav)
        try {
            if (reader.sampleRate != 16000 || reader.channels != 1 || reader.bitsPerSample != 16) {
                throw IllegalArgumentException(
                    "Ожидается WAV 16 кГц, моно, 16 бит: получено " +
                        "${reader.sampleRate} Гц / ${reader.channels} кан. / ${reader.bitsPerSample} бит",
                )
            }

            val recognizer = Recognizer(model, 16000.0f)
            recognizer.setWords(true)
            recognizer.setMaxAlternatives(0)

            val words = ArrayList<Word>(4096)
            val buffer = ShortArray(4000)
            val total = (reader.info.dataSize / 2).coerceAtLeast(1L)
            var processed = 0L
            var sinceFlush = 0

            try {
                while (true) {
                    if (isCancelled()) throw TranscriptionCancelledException()
                    val read = reader.read(buffer, buffer.size)
                    if (read <= 0) break

                    processed += read
                    sinceFlush += read

                    val endpoint = recognizer.acceptWaveForm(buffer, read)
                    if (endpoint) {
                        collect(recognizer.getResult(), words)
                        sinceFlush = 0
                    } else if (sinceFlush >= HARD_FLUSH_SAMPLES) {
                        collect(recognizer.getFinalResult(), words)
                        sinceFlush = 0
                    }

                    onProgress((processed.toFloat() / total).coerceIn(0f, 0.99f))
                }
                collect(recognizer.getFinalResult(), words)
            } finally {
                runCatching { recognizer.close() }
            }

            return TranscriptFormatter.format(words)
        } finally {
            reader.close()
        }
    }

    private fun collect(json: String, words: MutableList<Word>) {
        if (json.isBlank()) return
        val payload = runCatching { JSONObject(json) }.getOrNull() ?: return
        val array = payload.optJSONArray("result")
        if (array != null) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val word = item.optString("word").trim()
                if (word.isEmpty()) continue
                words.add(Word(word, item.optDouble("start", 0.0), item.optDouble("end", 0.0)))
            }
            return
        }
        val text = payload.optString("text").trim()
        if (text.isNotEmpty()) {
            text.split(' ').filter { it.isNotBlank() }.forEach { words.add(Word(it, 0.0, 0.0)) }
        }
    }

    private companion object {
        const val HARD_FLUSH_SAMPLES = 16000 * 25
    }
}
