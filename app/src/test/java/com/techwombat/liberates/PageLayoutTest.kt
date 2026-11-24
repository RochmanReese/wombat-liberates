package com.techwombat.liberates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLayoutTest {
    private val height = 2000
    private fun line(text: String, row: Int, left: Int = 50, right: Int = 1000) =
        OcrLine(text, left, 100 + row * 60, right, 140 + row * 60)

    @Test
    fun `prose page joins wrapped lines and drops the footer`() {
        val blocks = listOf(
            listOf(line("It was a dark and", 0), line("stormy night.", 1)),
            listOf(OcrLine("18 mins left in book", 50, 1950, 400, 1990), OcrLine("87%", 900, 1950, 1000, 1990)),
        )
        val page = PageLayout.analyze(blocks, height)
        assertFalse(page.isTable)
        assertEquals("It was a dark and stormy night.", page.text)
        assertEquals(87, page.progressPercent)
    }

    @Test
    fun `stat box is detected as a table and read row by row`() {
        // ML Kit returns columns as separate blocks: labels on the left, values on the right.
        val labels = listOf("Item Quality:", "Item Type:", "Slot:", "Defense:").mapIndexed { i, t -> line(t, i, 50, 400) }
        val values = listOf("Epic", "Crafted", "Head", "400").mapIndexed { i, t -> line(t, i, 500, 800) }
        val page = PageLayout.analyze(listOf(labels, values), height)
        assertTrue(page.isTable)
        assertEquals("Item Quality: Epic\nItem Type: Crafted\nSlot: Head\nDefense: 400", page.text)
    }

    @Test
    fun `two side-by-side lines on an otherwise normal page are not a table`() {
        val lines = (0 until 12).map { line("Line number $it of ordinary prose", it) } +
            line("Note", 12, 50, 300) + line("aside", 12, 600, 900)
        assertFalse(PageLayout.looksLikeTable(lines))
    }

    @Test
    fun `end of book screen is recognised`() {
        val blocks = listOf(listOf(line("Review this book on Amazon and Goodreads.", 0), line("On my profile as:", 1)))
        assertTrue(PageLayout.analyze(blocks, height).isEndOfBookScreen)
    }
}
