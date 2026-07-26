package com.techwombat.liberates

data class Chapter(
    val chapterIndex: Int,
    val title: String,
    val paragraphs: List<String>
)

object ChapterSegmenter {

    private val CHAPTER_PATTERNS = listOf(
        Regex("(?i)^chapter\\s+(?:\\d+|[ivxdlcbm]+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty).*$"),
        Regex("(?i)^prologue$"),
        Regex("(?i)^epilogue$"),
        Regex("(?i)^part\\s+(?:\\d+|[ivxdlcbm]+|one|two|three|four|five).*$"),
        Regex("(?i)^section\\s+(?:\\d+|[ivxdlcbm]+).*$")
    )

    fun isChapterHeading(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.length > 80) return false
        return CHAPTER_PATTERNS.any { it.matches(trimmed) }
    }

    fun segmentIntoChapters(paragraphs: List<String>): List<Chapter> {
        if (paragraphs.isEmpty()) return emptyList()

        val chapters = mutableListOf<Chapter>()
        var currentTitle = "Chapter 1"
        var currentParagraphs = mutableListOf<String>()
        var chapterCounter = 1

        for (p in paragraphs) {
            val trimmed = p.trim()
            if (isChapterHeading(trimmed)) {
                if (currentParagraphs.isNotEmpty()) {
                    chapters.add(
                        Chapter(
                            chapterIndex = chapterCounter++,
                            title = currentTitle,
                            paragraphs = currentParagraphs.toList()
                        )
                    )
                    currentParagraphs = mutableListOf()
                }
                currentTitle = trimmed
            } else {
                currentParagraphs.add(trimmed)
            }
        }

        if (currentParagraphs.isNotEmpty() || chapters.isEmpty()) {
            chapters.add(
                Chapter(
                    chapterIndex = chapterCounter,
                    title = currentTitle,
                    paragraphs = currentParagraphs.toList()
                )
            )
        }

        return chapters
    }
}
