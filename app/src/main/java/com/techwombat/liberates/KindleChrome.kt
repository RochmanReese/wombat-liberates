package com.techwombat.liberates

/**
 * Recognises Kindle's own on-screen text ("chrome") so it never ends up in the book:
 * the reading footer (time left, page/location, percentage), toolbar leftovers, and the end-of-book screen.
 */
object KindleChrome {
    private const val PERCENT = """\d{1,3}\s*/?\s*%"""
    private const val TIME_LEFT =
        // Numbers are often misread as letters ("Z hrs A mins"), so accept any short token for them.
        """[0-9A-Za-z]{1,4}\s*(?:min|mins|minutes|hr|hrs|hour|hours)(?:\s*(?:and\s*)?[0-9A-Za-z]{1,4}\s*(?:min|mins|minutes))?\s*left\s+in\s+(?:book|chapter)"""

    private const val PAGE_FOOTER =
        """(?i:page|loc(?:ation)?)\.?\s+\d+(?:\s+(?i:of)\s+\d[\d ]{0,7})?(?:\s*\S?\s*$PERCENT)?"""
    private const val PAGE_FOOTER_WITH_PERCENT =
        """(?i:page|loc(?:ation)?)\.?\s+\d+\s+(?i:of)\s+\d[\d ]{0,7}\s*\S?\s*$PERCENT"""

    /** Whole lines that are only Kindle chrome. */
    private val CHROME_LINES = listOf(
        Regex("""^$PERCENT$"""),
        Regex("""^(?i:$TIME_LEFT)\.?$"""),
        Regex("""^(?i:learning\s+reading\s+speed)\.*$"""),
        // "Page 375 of 376 • 100%", "Location 1 of 5167 0%", OCR variants like "Page 3 of 3 76 • 2%" or "of 517 e 1%".
        Regex("""^$PAGE_FOOTER$"""),
        Regex("""^(?i:back\s+to)\s+\d+$"""),
        Regex("""^Aa$"""),
    )

    /** Chrome fragments that OCR sometimes glues onto a real line of text. */
    private val INLINE_CHROME = listOf(
        Regex("""(?i)\b$TIME_LEFT\b\.?"""),
        Regex("""(?i)\blearning\s+reading\s+speed\b\.*"""),
        Regex("""\b$PAGE_FOOTER_WITH_PERCENT"""),
    )

    /** Text from Kindle's "About this book / Review" screen after the last page. Two must appear together. */
    private val END_SCREEN_PHRASES = listOf(
        "review this book on amazon",
        "follow the author to get new release updates",
        "on my profile as",
        "not on profile",
        "about the author",
        "before you go",
        "rate this book",
    )
    private const val END_SCREEN_MIN_PHRASES = 2

    fun isChromeLine(line: String): Boolean {
        val text = line.trim()
        return text.isNotEmpty() && CHROME_LINES.any { it.matches(text) }
    }

    /** Reading progress shown in the footer, e.g. "87%", "87/%", "Page 375 of 376 • 100%". */
    fun progressPercent(line: String): Int? {
        val text = line.trim()
        Regex("""(\d{1,3})\s*/?\s*%$""").find(text)?.let { match ->
            if (isChromeLine(text)) return match.groupValues[1].toInt().takeIf { it <= 100 }
        }
        Regex("""^(?i:page)\s+(\d+)\s+(?i:of)\s+(\d+)$""").find(text)?.let { match ->
            val (page, total) = match.destructured
            if (total.toInt() > 0) return (page.toInt() * 100 / total.toInt()).coerceAtMost(100)
        }
        return null
    }

    fun isEndOfBookScreen(text: String): Boolean {
        val lower = text.lowercase()
        return END_SCREEN_PHRASES.count { it in lower } >= END_SCREEN_MIN_PHRASES
    }

    /**
     * Removes chrome from already-assembled text: whole chrome lines and paragraphs, and fragments
     * glued into real sentences (e.g. "18 mins left in book leather." becomes "leather.").
     */
    fun strip(text: String): String {
        val paragraphs = text.split(Regex("""\n\s*\n""")).mapNotNull { paragraph ->
            val lines = paragraph.lines().filterNot(::isChromeLine).map { line ->
                INLINE_CHROME.fold(line) { current, pattern -> current.replace(pattern, " ") }
                    .replace(Regex("""[ \t]{2,}"""), " ")
                    .trim()
            }.filter { it.isNotEmpty() }
            lines.joinToString("\n").takeIf { it.isNotBlank() }
        }
        return paragraphs.joinToString("\n\n")
    }
}
