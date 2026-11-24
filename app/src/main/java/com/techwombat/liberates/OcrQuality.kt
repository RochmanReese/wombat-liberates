package com.techwombat.liberates

/**
 * Rough, offline estimate of OCR quality: the share of words that look like recognition errors.
 * Used to suggest Ollama only when device OCR plus local cleanup is not good enough.
 */
object OcrQuality {
    enum class Level(val heading: String) {
        GOOD("Looks good"),
        FAIR("A few problems"),
        POOR("Poor: Ollama cleanup recommended"),
    }

    data class Report(
        val words: Int,
        val suspiciousWords: Int,
        /** Most frequent suspicious words with their counts, most common first. */
        val examples: List<Pair<String, Int>>,
    ) {
        val percent: Double get() = if (words == 0) 0.0 else suspiciousWords * 100.0 / words
        val level: Level
            get() = when {
                percent < FAIR_THRESHOLD_PERCENT -> Level.GOOD
                percent < POOR_THRESHOLD_PERCENT -> Level.FAIR
                else -> Level.POOR
            }
    }

    const val FAIR_THRESHOLD_PERCENT = 0.5
    const val POOR_THRESHOLD_PERCENT = 2.0
    private const val MAX_EXAMPLES = 15

    private const val ODD_SYMBOLS = "|©®¢¥§¤~^_<>{}\\¦°±µ¶¬¡¿«»"
    private const val EDGE_PUNCTUATION = "\"'“”‘’()[],.;:!?…—–-*"
    private val SINGLE_LETTER_WORDS = setOf("a", "A", "I", "O")
    private val NUMBER_WITH_SUFFIX = Regex("^\\d+(st|nd|rd|th|s|am|pm|AM|PM|km|kg|cm|mm|m)?$")
    private val LOWER_UPPER_LOWER = Regex("[a-z][A-Z][a-z]")

    fun assess(text: String): Report {
        val tokens = Regex("\\S+").findAll(text).map { it.value }.toList()
        val counts = linkedMapOf<String, Int>()
        tokens.forEach { token ->
            if (isSuspicious(token)) {
                // Group "c0me," with "c0me" in the examples, but keep bare symbols like "|" visible.
                val key = token.trim { it in EDGE_PUNCTUATION }.ifEmpty { token }
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        return Report(
            words = tokens.size,
            suspiciousWords = counts.values.sum(),
            examples = counts.entries.sortedByDescending { it.value }.take(MAX_EXAMPLES).map { it.key to it.value },
        )
    }

    fun isSuspicious(token: String): Boolean {
        if (token.any { it in ODD_SYMBOLS }) return true
        val word = token.trim { it in EDGE_PUNCTUATION }
        if (word.isEmpty()) return false

        val hasLetter = word.any(Char::isLetter)
        val hasDigit = word.any(Char::isDigit)
        if (hasLetter && hasDigit && !NUMBER_WITH_SUFFIX.matches(word)) return true

        // "yOur", "stOod": a capital inside a lowercase word. Capitalised words like McDonald or WillCo are usually names.
        if (word[0].isLowerCase() && LOWER_UPPER_LOWER.containsMatchIn(word)) return true

        // Stray single letters, but not initials like "J." in "J. K. Rowling".
        if (word.length == 1 && word[0].isLetter() && word !in SINGLE_LETTER_WORDS) {
            val isInitial = word[0].isUpperCase() && token.endsWith(".")
            if (!isInitial) return true
        }
        return false
    }

    /** Short human summary, e.g. "Looks good: 0.2% of 6,120 words look wrong." */
    fun describe(report: Report): String {
        if (report.words == 0) return "No text to check."
        val percent = String.format(java.util.Locale.US, "%.1f", report.percent)
        val base = "${report.level.heading}: $percent% of ${"%,d".format(report.words)} words look wrong."
        if (report.examples.isEmpty()) return base
        val examples = report.examples.joinToString(", ") { (word, count) -> if (count > 1) "$word ×$count" else word }
        return "$base\nMost common: $examples"
    }
}
