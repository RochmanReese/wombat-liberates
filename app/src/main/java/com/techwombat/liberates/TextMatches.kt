package com.techwombat.liberates

/** One regex match: its text, where it is, and its groups (index 0 is the whole match). */
data class TextMatch(val value: String, val range: IntRange, val groupValues: List<String>)

/**
 * All matches of this pattern in [text], found with a single matcher.
 *
 * Use this instead of Kotlin's Regex.findAll on anything longer than a page: on Android, findAll creates
 * a new ICU matcher for every match, and each one copies the whole text into native memory. For a whole
 * book (120,000 words) that is 120,000 copies of the book, so it effectively never finishes.
 */
fun Regex.matchesIn(text: CharSequence): List<TextMatch> {
    val matcher = toPattern().matcher(text)
    val matches = ArrayList<TextMatch>()
    while (matcher.find()) {
        if (matcher.end() == matcher.start()) continue // patterns used here never match empty text
        matches += TextMatch(matcher.group(), matcher.start() until matcher.end(), List(matcher.groupCount() + 1) { matcher.group(it).orEmpty() })
    }
    return matches
}
