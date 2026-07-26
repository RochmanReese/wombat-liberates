package com.techwombat.liberates

object TextCleaner {

    private val HEADER_FOOTER_PATTERNS = listOf(
        Regex("(?i)^loc(?:ation)?\\s+\\d+(?:\\s+of\\s+\\d+)?$"),
        Regex("(?i)^page\\s+\\d+(?:\\s+of\\s+\\d+)?$"),
        Regex("^\\d{1,3}%\\s*$"),
        Regex("^\\d+$"),
        Regex("(?i)^\\d+\\s+mins?\\s+left(?:\\s+in\\s+(?:book|chapter))?$"),
        Regex("(?i)^kindle\\s+edition$"),
        Regex("(?i)^add\\s+bookmark$"),
        Regex("(?i)^close\\s+book\\.?$"),
        Regex("(?i)^table\\s+of\\s+contents$"),
        Regex("(?i)^in-book\\s+search$"),
        Regex("(?i)^annotations$"),
        Regex("(?i)^reading\\s+settings$"),
        Regex("(?i)^more\\s+options$"),
        Regex("(?i)^birds\\s+eye\\s+view$"),
        Regex("(?i)^reading\\s+progress\\s+bar$"),
        Regex("(?i)^current\\s+location\\.?$"),
        Regex("(?i)^back\\s+to.*$")
    )

    private val TERMINAL_PUNCTUATION = setOf('.', '!', '?', '"', '”', '’')

    fun isHeaderOrFooterLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return true
        return HEADER_FOOTER_PATTERNS.any { it.matches(trimmed) }
    }

    fun cleanPageLines(lines: List<String>): List<String> {
        return lines
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isHeaderOrFooterLine(it) }
    }

    fun stitchPageText(pagesLines: List<List<String>>): List<String> {
        val cleanedPages = pagesLines.map { cleanPageLines(it) }
        val stitchedParagraphs = mutableListOf<String>()

        var currentParagraph = StringBuilder()

        for (pageLines in cleanedPages) {
            for (line in pageLines) {
                if (currentParagraph.isEmpty()) {
                    currentParagraph.append(line)
                } else {
                    val lastChar = currentParagraph.last()

                    if (lastChar == '-') {
                        // Trailing hyphenation: differ- + ent -> different
                        currentParagraph.deleteCharAt(currentParagraph.length - 1)
                        currentParagraph.append(line)
                    } else if (!TERMINAL_PUNCTUATION.contains(lastChar) && line.firstOrNull()?.isLowerCase() == true) {
                        // Sentence split across boundary: join with space
                        currentParagraph.append(" ").append(line)
                    } else if (!TERMINAL_PUNCTUATION.contains(lastChar)) {
                        // Continuation line: join with space
                        currentParagraph.append(" ").append(line)
                    } else {
                        // Completed sentence/paragraph: push to list
                        stitchedParagraphs.add(currentParagraph.toString())
                        currentParagraph = StringBuilder(line)
                    }
                }
            }
        }

        if (currentParagraph.isNotEmpty()) {
            stitchedParagraphs.add(currentParagraph.toString())
        }

        return stitchedParagraphs
    }
}
