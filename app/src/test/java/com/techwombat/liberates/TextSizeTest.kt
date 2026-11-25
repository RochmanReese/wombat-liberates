package com.techwombat.liberates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSizeTest {
    private fun body(row: Int, height: Int) =
        OcrLine("A full line of ordinary body text here.", 50, 100 + row * 80, 1000, 100 + row * 80 + height)

    @Test
    fun `body line height is the median of long lines, ignoring headings and short lines`() {
        val lines = listOf(
            OcrLine("CHAPTER ONE", 50, 0, 600, 90), // big heading, short
            OcrLine("“No.”", 50, 95, 200, 125), // short dialogue
            body(1, 30), body(2, 31), body(3, 30), body(4, 32), body(5, 60), // one odd tall line
        )
        assertEquals(31, TextSize.bodyLineHeight(lines))
    }

    @Test
    fun `too little body text gives no measurement`() {
        assertNull(TextSize.bodyLineHeight(listOf(body(0, 30), body(1, 30))))
        assertNull(TextSize.median(emptyList()))
    }

    @Test
    fun `page analysis carries the body line height, but not for tables`() {
        val page = PageLayout.analyze(listOf((0..5).map { body(it, 40) }), 2000)
        assertEquals(40, page.bodyLineHeight)
    }

    @Test
    fun `advice covers each size band`() {
        assertTrue(TextSize.advice(15).contains("too small"))      // 11 px letters
        assertTrue(TextSize.advice(24).contains("small edge"))     // 17 px
        assertTrue(TextSize.advice(32).contains("good size"))      // 22 px
        assertTrue(TextSize.advice(50).contains("bigger than OCR needs")) // 35 px
        assertTrue(TextSize.advice(32).contains("about 22 pixels"))
    }

    @Test
    fun `scan summary keeps the line height`() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val library = Library(dir)
            val book = library.create("T", "")
            book.scanSummary = ScanSummary(3, 3, 0, "done", 1L, lineHeightPx = 33)
            assertEquals(33, Library(dir).open(book.key)?.scanSummary?.lineHeightPx)
            book.scanSummary = ScanSummary(3, 3, 0, "done", 1L)
            assertNull(Library(dir).open(book.key)?.scanSummary?.lineHeightPx)
        } finally {
            dir.deleteRecursively()
        }
    }
}
