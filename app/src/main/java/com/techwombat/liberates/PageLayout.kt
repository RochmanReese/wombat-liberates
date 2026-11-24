package com.techwombat.liberates

import kotlin.math.abs

/** One OCR line with its position in the (cropped) screenshot, in pixels. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY: Float get() = (top + bottom) / 2f
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/** What a captured screen contains, after Kindle's own footer and header are removed. */
data class PageAnalysis(
    val text: String,
    val isTable: Boolean,
    /** Reading progress from Kindle's footer, if it was visible. */
    val progressPercent: Int?,
    val isEndOfBookScreen: Boolean,
    /** Number of picture placeholders in [text], in reading order. */
    val pictureCount: Int = 0,
) {
    /** Text without picture placeholders, for decisions that are about the words on the page. */
    val wordsOnly: String get() = Illustrations.removeMarkers(text) { false }
}

/** Turns OCR blocks into page text: drops Kindle chrome, detects tables and reads them row by row. */
object PageLayout {
    /** Short lines this close to the top or bottom edge are Kindle's header/footer, not book text. */
    private const val TOP_BAND = 0.02f
    private const val BOTTOM_BAND = 0.95f
    private const val MAX_BAND_LINE_LENGTH = 40
    private const val MIN_TABLE_ROWS = 3
    private const val MIN_TABLE_ROW_FRACTION = 0.25

    /** Placeholder for the n-th picture on a page; the scanner swaps it for the saved picture's marker. */
    fun picturePlaceholder(index: Int) = Illustrations.marker("#$index")

    /**
     * @param pictures picture areas found on the page (same pixel coordinates as the OCR lines); each becomes a
     * [picturePlaceholder] paragraph between the text above and below it.
     */
    fun analyze(blocks: List<List<OcrLine>>, imageHeight: Int, pictures: List<Box> = emptyList()): PageAnalysis {
        val cleanedBlocks = blocks.map { block -> block.filter { it.text.isNotBlank() } }.filter { it.isNotEmpty() }
        val allLines = cleanedBlocks.flatten()
        val progress = allLines.mapNotNull { KindleChrome.progressPercent(it.text) }.maxOrNull()
        val endScreen = KindleChrome.isEndOfBookScreen(allLines.joinToString("\n") { it.text })

        val bookBlocks = cleanedBlocks
            .map { block -> block.filterNot { isChrome(it, imageHeight) } }
            .filter { it.isNotEmpty() }
        val bookLines = bookBlocks.flatten()
        val table = looksLikeTable(bookLines)
        fun assemble(segment: List<List<OcrLine>>): String =
            KindleChrome.strip(if (table) tableText(segment.flatten()) else proseText(segment))

        // Split the text at each picture so pictures land between the text above and below them.
        val sortedPictures = pictures.sortedBy { it.top }
        val parts = mutableListOf<String>()
        sortedPictures.indices.forEach { index ->
            val above = bookBlocks.filter { block ->
                val top = block.minOf { it.top }
                top < sortedPictures[index].top && (index == 0 || top >= sortedPictures[index - 1].top)
            }
            parts += assemble(above)
            parts += picturePlaceholder(index)
        }
        parts += assemble(bookBlocks.filter { block -> sortedPictures.isEmpty() || block.minOf { it.top } >= sortedPictures.last().top })
        val text = parts.filter { it.isNotBlank() }.joinToString("\n\n")
        return PageAnalysis(text, table, progress, endScreen, sortedPictures.size)
    }

    private fun isChrome(line: OcrLine, imageHeight: Int): Boolean {
        if (KindleChrome.isChromeLine(line.text)) return true
        val inBand = line.centerY < imageHeight * TOP_BAND || line.centerY > imageHeight * BOTTOM_BAND
        return inBand && line.text.trim().length <= MAX_BAND_LINE_LENGTH
    }

    /** Groups lines into visual rows (same height on screen), each row sorted left to right. */
    fun rows(lines: List<OcrLine>): List<List<OcrLine>> {
        if (lines.isEmpty()) return emptyList()
        val medianHeight = lines.map { it.height }.sorted()[lines.size / 2]
        val rows = mutableListOf<MutableList<OcrLine>>()
        lines.sortedBy { it.centerY }.forEach { line ->
            val row = rows.lastOrNull()
            if (row != null && abs(row.map { it.centerY }.average() - line.centerY) < medianHeight * 0.5) row += line else rows += mutableListOf(line)
        }
        return rows.map { row -> row.sortedBy { it.left } }
    }

    /** Tables put several separate lines side by side on the same row; ordinary prose has one line per row. */
    fun looksLikeTable(lines: List<OcrLine>): Boolean {
        val rows = rows(lines)
        val sideBySide = rows.count { row -> row.size >= 2 && row.zipWithNext().all { (a, b) -> a.right <= b.left } }
        return sideBySide >= MIN_TABLE_ROWS && sideBySide >= rows.size * MIN_TABLE_ROW_FRACTION
    }

    /**
     * Reads a table row by row ("Item Quality: Epic") instead of column by column. Single-line rows that
     * follow each other closely (wrapped prose under or beside the table) are joined back into sentences.
     */
    fun tableText(lines: List<OcrLine>): String {
        val rows = rows(lines)
        if (rows.isEmpty()) return ""
        val medianHeight = lines.map { it.height }.sorted()[lines.size / 2]
        return buildString {
            var previous: List<OcrLine>? = null
            rows.forEach { row ->
                val rowText = row.map { it.text.trim() }.reduce { acc, next -> if (acc.endsWith(":")) "$acc $next" else "$acc · $next" }
                val last = previous
                if (last != null) {
                    val gap = row.minOf { it.top } - last.maxOf { it.bottom }
                    when {
                        gap > medianHeight * 1.5 -> append("\n\n")
                        last.size == 1 && row.size == 1 && !endsSentence(this) -> if (endsWith("-")) deleteCharAt(length - 1) else append(' ')
                        else -> append('\n')
                    }
                }
                append(rowText)
                previous = row
            }
        }
    }

    /** Ordinary pages: join the lines of each OCR block, and join blocks that continue a sentence. */
    fun proseText(blocks: List<List<OcrLine>>): String = buildString {
        blocks.map { block -> joinVisualLines(block.map { it.text.trim() }) }.forEach { block ->
            if (isNotEmpty()) {
                if (shouldJoinBlocks(toString(), block)) {
                    if (endsWith("-")) deleteCharAt(length - 1) else append(' ')
                } else {
                    append("\n\n")
                }
            }
            append(block)
        }
    }

    private fun endsSentence(text: CharSequence): Boolean = text.trimEnd().lastOrNull()?.let { it in ".!?\"”:" } ?: true

    private fun shouldJoinBlocks(previous: String, next: String): Boolean {
        if (previous.endsWith("-")) return true
        val previousLast = previous.lastOrNull() ?: return false
        return next.firstOrNull()?.isLowerCase() == true && previousLast !in ".!?\"”"
    }

    private fun joinVisualLines(lines: List<String>): String = buildString {
        lines.forEachIndexed { index, line ->
            when {
                index == 0 -> append(line)
                endsWith("-") -> {
                    deleteCharAt(length - 1)
                    append(line)
                }
                else -> {
                    append(' ')
                    append(line)
                }
            }
        }
    }
}
