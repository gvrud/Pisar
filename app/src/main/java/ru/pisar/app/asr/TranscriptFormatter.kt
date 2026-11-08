package ru.pisar.app.asr

object TranscriptFormatter {

    private const val PERIOD_GAP = 0.55
    private const val COMMA_GAP = 0.28
    private const val MAX_WORDS_PER_SENTENCE = 22
    private const val SENTENCES_PER_PARAGRAPH = 4

    fun format(words: List<Transcriber.Word>): String {
        if (words.isEmpty()) return ""

        val sentences = ArrayList<String>()
        val buffer = StringBuilder()
        var count = 0
        var previousEnd = -1.0

        fun flush() {
            val text = buffer.toString().trim()
            if (text.isNotEmpty()) sentences.add(ensureTerminal(text))
            buffer.setLength(0)
            count = 0
        }

        for (word in words) {
            val token = word.text.trim()
            if (token.isEmpty() || token == "[unk]") continue

            if (buffer.isEmpty()) {
                buffer.append(capitalize(token))
                count = 1
            } else {
                val gap = if (previousEnd >= 0 && word.start > 0) word.start - previousEnd else 0.0
                when {
                    gap >= PERIOD_GAP -> {
                        flush()
                        buffer.append(capitalize(token))
                        count = 1
                    }

                    gap >= COMMA_GAP -> {
                        buffer.append(", ").append(token)
                        count++
                    }

                    else -> {
                        buffer.append(' ').append(token)
                        count++
                    }
                }
            }

            if (word.end > 0) previousEnd = word.end
            if (count >= MAX_WORDS_PER_SENTENCE) flush()
        }
        flush()

        if (sentences.isEmpty()) return ""
        return sentences
            .chunked(SENTENCES_PER_PARAGRAPH)
            .joinToString("\n\n") { paragraph -> paragraph.joinToString(" ") }
    }

    private fun ensureTerminal(sentence: String): String {
        val last = sentence.lastOrNull() ?: return sentence
        return if (last == '.' || last == '!' || last == '?' || last == '…') sentence else "$sentence."
    }

    private fun capitalize(word: String): String {
        if (word.isEmpty()) return word
        return word.substring(0, 1).uppercase() + word.substring(1)
    }
}
