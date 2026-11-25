package com.techwombat.liberates

/**
 * How big Kindle's text is on the screenshot, for advice on the best Kindle font size.
 *
 * ML Kit text recognition needs letters roughly 16+ pixels tall and gains little beyond about 24 to 28.
 * OCR gives line boxes (top of tall letters to bottom of tails), so a capital letter is estimated as
 * about 70% of the line box height. What counts is pixels on the screenshot, so the right Kindle font
 * size differs between phones with different screen resolutions.
 */
object TextSize {
    /** Lines shorter than this are skipped: headings and short dialogue lines are often a different size. */
    private const val MIN_BODY_LINE_CHARS = 25
    private const val CAP_HEIGHT_PER_LINE_BOX = 0.7

    const val TOO_SMALL_BELOW = 14
    const val GOOD_FROM = 18
    const val LARGER_THAN_NEEDED_ABOVE = 28

    /** Median line box height of ordinary body text on one page, in pixels, or null if there is too little. */
    fun bodyLineHeight(lines: List<OcrLine>): Int? {
        val heights = lines.filter { it.text.trim().length >= MIN_BODY_LINE_CHARS }.map { it.height }.sorted()
        return if (heights.size < 3) null else heights[heights.size / 2]
    }

    /** Median of per-page values (so one odd page doesn't decide it). */
    fun median(values: List<Int>): Int? = values.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

    fun letterHeight(lineHeight: Int): Int = Math.round(lineHeight * CAP_HEIGHT_PER_LINE_BOX).toInt()

    /** One or two sentences for the user about the measured size and what to change in Kindle. */
    fun advice(lineHeight: Int): String {
        val letters = letterHeight(lineHeight)
        val measured = "Letters are about $letters pixels tall on this phone's screen"
        return when {
            letters < TOO_SMALL_BELOW ->
                "$measured: too small for reliable OCR. Punctuation and letters like rn/m get misread. " +
                    "Make Kindle's font size bigger (Aa menu) by one or two steps."
            letters < GOOD_FROM ->
                "$measured: readable, but at the small edge. Punctuation may be missed now and then. " +
                    "One step bigger in Kindle is safer."
            letters <= LARGER_THAN_NEEDED_ABOVE ->
                "$measured: a good size for OCR. Keep this Kindle font size for the whole book."
            else ->
                "$measured: bigger than OCR needs. Making Kindle's font one or two steps smaller fits more text " +
                    "on each screen, so the scan is quicker, without losing accuracy."
        }
    }
}
