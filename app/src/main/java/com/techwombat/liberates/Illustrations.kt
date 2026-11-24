package com.techwombat.liberates

import kotlin.math.abs

/** A rectangle in pixels (right/bottom exclusive). */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Illustrations in the text are stored as their own paragraph, "[[image:img-0001.jpg]]", pointing at a
 * picture file saved in the book's images folder.
 */
object Illustrations {
    private val MARKER = Regex("""\[\[image:([A-Za-z0-9#._-]+)]]""")

    fun marker(id: String) = "[[image:$id]]"

    fun isMarker(text: String): Boolean = MARKER.matches(text.trim())

    fun ids(text: String): List<String> = MARKER.matchesIn(text).map { it.groupValues[1] }

    /** Replaces markers with a short readable placeholder, for on-screen text views. */
    fun readable(text: String, coverId: String? = null): String =
        MARKER.replace(text) { match -> if (match.groupValues[1] == coverId) "[Cover picture]" else "[Illustration]" }

    /** Removes the markers whose pictures fail [keep] (deleted, or chosen as the cover). */
    fun removeMarkers(text: String, keep: (String) -> Boolean): String =
        text.split(Regex("""\n\s*\n""")).filterNot { paragraph ->
            val id = MARKER.matchEntire(paragraph.trim())?.groupValues?.get(1)
            id != null && !keep(id)
        }.joinToString("\n\n")

    /**
     * AI cleanup must not lose or invent picture markers: drop markers that were not in the input and add back,
     * as their own paragraphs at the end of the chunk, any that went missing.
     */
    fun restoreMarkers(input: String, output: String): String {
        val expected = ids(input)
        var fixed = MARKER.replace(output) { match -> if (match.groupValues[1] in expected) match.value else "" }
        val missing = expected.filterNot { it in ids(fixed) }
        if (missing.isNotEmpty()) fixed = fixed.trimEnd() + missing.joinToString("") { "\n\n" + marker(it) }
        return fixed.replace(Regex("""\n{3,}"""), "\n\n").trim()
    }
}

/**
 * Finds pictures on a captured page: large areas that contain visual detail but no OCR text.
 * Works on a small downscaled copy of the screenshot so it is cheap to run on every page.
 */
object IllustrationDetector {
    /** Colour distance from the page background that counts as "something drawn here". */
    private const val INK_DISTANCE = 48
    /** Share of a row's cells that must contain ink for the row to belong to a picture. */
    private const val MIN_ROW_INK = 0.06
    /** Picture rows may have a few near-empty rows inside them (e.g. a light sky). */
    private const val MAX_GAP_ROWS = 3
    /** Ignore ornaments and divider lines: pictures must be at least this tall and wide (share of the page). */
    private const val MIN_HEIGHT_FRACTION = 0.12
    private const val MIN_WIDTH_FRACTION = 0.25

    /**
     * @param pixels ARGB pixels of the downscaled page, row by row.
     * @param textBoxes OCR line boxes, already scaled to the same small size.
     * @return picture areas in the small image's coordinates.
     */
    fun detect(pixels: IntArray, width: Int, height: Int, textBoxes: List<Box>): List<Box> {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return emptyList()
        val background = backgroundColour(pixels, width, height)
        val textRow = BooleanArray(height)
        textBoxes.forEach { box ->
            for (y in (box.top - 1).coerceAtLeast(0) until (box.bottom + 1).coerceAtMost(height)) textRow[y] = true
        }
        val ink = BooleanArray(width * height) { index -> distance(pixels[index], background) > INK_DISTANCE }
        val rowInk = DoubleArray(height) { y -> if (textRow[y]) 0.0 else (0 until width).count { x -> ink[y * width + x] }.toDouble() / width }

        val pictures = mutableListOf<Box>()
        var y = 0
        while (y < height) {
            if (rowInk[y] < MIN_ROW_INK) {
                y++
                continue
            }
            val start = y
            var end = y
            var gap = 0
            while (y < height && !textRow[y] && gap <= MAX_GAP_ROWS) {
                if (rowInk[y] >= MIN_ROW_INK) {
                    end = y
                    gap = 0
                } else {
                    gap++
                }
                y++
            }
            if (end - start + 1 >= height * MIN_HEIGHT_FRACTION) {
                val columns = (0 until width).filter { x -> (start..end).any { row -> ink[row * width + x] } }
                if (columns.isNotEmpty()) {
                    val box = Box(
                        left = (columns.first() - 1).coerceAtLeast(0),
                        top = (start - 1).coerceAtLeast(0),
                        right = (columns.last() + 2).coerceAtMost(width),
                        bottom = (end + 2).coerceAtMost(height),
                    )
                    if (box.width >= width * MIN_WIDTH_FRACTION) pictures += box
                }
            }
            y = maxOf(y, end + 1)
        }
        return pictures
    }

    /** The most common colour around the page edge (white, sepia or black page). */
    private fun backgroundColour(pixels: IntArray, width: Int, height: Int): Int {
        val counts = HashMap<Int, Int>()
        fun add(pixel: Int) {
            val key = (pixel shr 4) and 0x0F0F0F // group similar colours
            counts[key] = (counts[key] ?: 0) + 1
        }
        for (x in 0 until width) {
            add(pixels[x])
            add(pixels[(height - 1) * width + x])
        }
        for (y in 0 until height) {
            add(pixels[y * width])
            add(pixels[y * width + width - 1])
        }
        val key = counts.maxByOrNull { it.value }?.key ?: return 0xFFFFFF
        return (key shl 4) or 0x080808
    }

    private fun distance(a: Int, b: Int): Int =
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) + abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))
}
