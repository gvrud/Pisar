package ru.pisar.app.nlp

import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class SummaryResult(
    val shortText: String,
    val bullets: List<String>,
    val keywords: List<String>,
)

object TextAnalyzer {

    private val sentenceSplitter = Regex("(?<=[.!?…])\\s+|\\n+")
    private val wordRegex = Regex("[а-яёa-z0-9]+", RegexOption.IGNORE_CASE)

    private val endings = listOf(
        "ениями", "ениях", "ования", "ованиями", "иями", "ями", "ами", "ыми", "ими",
        "ание", "ания", "аний", "анию", "ением", "ении", "ению", "ения", "ение",
        "ость", "ости", "остью", "остей", "остям", "остями", "остях",
        "ешь", "ишь", "ует", "уют", "ает", "ают", "ять", "ать", "ить", "ыть",
        "ется", "ются", "ится", "ятся", "лся", "лась", "лись", "лось",
        "ого", "его", "ему", "ому", "ыми", "ими", "ых", "их", "ую", "юю",
        "ов", "ев", "ий", "ый", "ая", "яя", "ое", "ее", "ые", "ие",
        "ем", "ом", "ах", "ях", "ам", "ям", "ой", "ей", "ым", "им",
        "а", "я", "ы", "и", "о", "е", "у", "ю",
    ).sortedByDescending { it.length }

    fun summarize(text: String, ratio: Double = 0.22): SummaryResult? {
        val sentences = splitSentences(text)
        if (sentences.size < 3) return null

        val surfaces = HashMap<String, String>()
        val tokens = sentences.map { tokenize(it, surfaces) }
        if (tokens.all { it.isEmpty() }) return null

        val count = sentences.size
        val documentFrequency = HashMap<String, Int>()
        tokens.forEach { list ->
            list.toSet().forEach { term -> documentFrequency[term] = (documentFrequency[term] ?: 0) + 1 }
        }

        val vectors = tokens.map { list ->
            val tf = HashMap<String, Int>()
            list.forEach { term -> tf[term] = (tf[term] ?: 0) + 1 }
            val vector = HashMap<String, Double>(tf.size)
            tf.forEach { (term, frequency) ->
                val idf = ln((count + 1.0) / ((documentFrequency[term] ?: 1) + 1.0)) + 1.0
                vector[term] = frequency * idf
            }
            vector
        }

        val norms = vectors.map { vector ->
            sqrt(vector.values.sumOf { it * it }).coerceAtLeast(1e-9)
        }

        val scores = if (count <= 900) {
            val similarity = Array(count) { DoubleArray(count) }
            for (i in 0 until count) {
                for (j in i + 1 until count) {
                    val value = cosine(vectors[i], vectors[j], norms[i], norms[j])
                    similarity[i][j] = value
                    similarity[j][i] = value
                }
            }
            textRank(similarity, count)
        } else {
            val maxNorm = norms.maxOrNull() ?: 1.0
            DoubleArray(count) { vectorLength(vectors[it]) / maxNorm }
        }

        val target = (count * ratio).roundToInt().coerceIn(2, 8).coerceAtMost(count - 1)
        val selected = selectSentences(scores, vectors, norms, target)

        val bullets = selected.sorted().map { sentences[it] }
        val shortText = bullets.take(2).joinToString(" ")

        val totals = HashMap<String, Double>()
        vectors.forEach { vector ->
            vector.forEach { (term, weight) -> totals[term] = (totals[term] ?: 0.0) + weight }
        }
        val keywords = totals.entries
            .sortedByDescending { it.value }
            .take(10)
            .mapNotNull { entry -> surfaces[entry.key] }
            .distinct()
            .take(8)
            .map { capitalize(it) }

        if (bullets.isEmpty()) return null
        return SummaryResult(shortText, bullets, keywords)
    }

    private fun splitSentences(text: String): List<String> {
        val normalized = text
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{2,}"), "\n")
        return normalized.split(sentenceSplitter)
            .map { it.trim() }
            .filter { it.length in 25..900 }
            .map { if (it.lastOrNull() in listOf('.', '!', '?', '…')) it else "$it." }
    }

    private fun tokenize(sentence: String, surfaces: MutableMap<String, String>): List<String> {
        val result = ArrayList<String>()
        for (match in wordRegex.findAll(sentence.lowercase())) {
            val raw = match.value
            if (raw.length < 3) continue
            if (RussianStopWords.contains(raw)) continue
            if (raw.all { it.isDigit() }) continue
            if (raw.none { it.isLetter() }) continue

            val stemmed = stem(raw)
            result.add(stemmed)
            val existing = surfaces[stemmed]
            if (existing == null || existing.length < raw.length) surfaces[stemmed] = raw
        }
        return result
    }

    private fun stem(word: String): String {
        for (ending in endings) {
            if (word.length - ending.length >= 4 && word.endsWith(ending)) {
                return word.dropLast(ending.length)
            }
        }
        return word
    }

    private fun cosine(
        first: Map<String, Double>,
        second: Map<String, Double>,
        firstNorm: Double,
        secondNorm: Double,
    ): Double {
        val small = if (first.size <= second.size) first else second
        val large = if (first.size <= second.size) second else first
        var dot = 0.0
        small.forEach { (term, weight) ->
            val other = large[term] ?: return@forEach
            dot += weight * other
        }
        return dot / (firstNorm * secondNorm)
    }

    private fun vectorLength(vector: Map<String, Double>): Double =
        sqrt(vector.values.sumOf { it * it }).coerceAtLeast(1e-9)

    private fun textRank(similarity: Array<DoubleArray>, count: Int): DoubleArray {
        val damping = 0.85
        var scores = DoubleArray(count) { 1.0 }
        val outSum = DoubleArray(count) { index -> similarity[index].sum() }

        repeat(25) {
            val next = DoubleArray(count) { 1.0 - damping }
            for (target in 0 until count) {
                for (source in 0 until count) {
                    if (source == target) continue
                    val total = outSum[source]
                    if (total <= 0.0) continue
                    next[target] += damping * similarity[source][target] / total * scores[source]
                }
            }
            scores = next
        }
        return scores
    }

    private fun selectSentences(
        scores: DoubleArray,
        vectors: List<Map<String, Double>>,
        norms: List<Double>,
        count: Int,
    ): List<Int> {
        val selected = ArrayList<Int>(count)
        val candidates = scores.indices.toMutableList()
        val maxScore = scores.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val lambda = 0.7

        repeat(count) {
            var best = -1
            var bestValue = Double.NEGATIVE_INFINITY
            for (candidate in candidates) {
                var maxSimilarity = 0.0
                for (chosen in selected) {
                    val value = cosine(vectors[candidate], vectors[chosen], norms[candidate], norms[chosen])
                    if (value > maxSimilarity) maxSimilarity = value
                }
                val relevance = scores[candidate] / maxScore
                val value = lambda * relevance - (1 - lambda) * maxSimilarity
                if (value > bestValue) {
                    bestValue = value
                    best = candidate
                }
            }
            if (best < 0) return@repeat
            selected.add(best)
            candidates.remove(best)
        }
        return selected
    }

    private fun capitalize(word: String): String {
        if (word.isEmpty()) return word
        return word.substring(0, 1).uppercase() + word.substring(1)
    }
}
