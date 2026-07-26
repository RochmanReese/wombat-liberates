package com.techwombat.liberates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCleanerTest {

    @Test
    fun testHeaderFooterDetection() {
        assertTrue(TextCleaner.isHeaderOrFooterLine("Location 142 of 3200"))
        assertTrue(TextCleaner.isHeaderOrFooterLine("Page 45"))
        assertTrue(TextCleaner.isHeaderOrFooterLine("85%"))
        assertTrue(TextCleaner.isHeaderOrFooterLine("12"))
        assertTrue(TextCleaner.isHeaderOrFooterLine("14 mins left in chapter"))
    }

    @Test
    fun testCleanPageLinesStripsNoise() {
        val rawLines = listOf(
            "Book Title Header",
            "Location 100 of 5000",
            "It was a dark and stormy night.",
            "Page 12",
            "85%"
        )
        val cleaned = TextCleaner.cleanPageLines(rawLines)

        assertEquals(2, cleaned.size)
        assertEquals("Book Title Header", cleaned[0])
        assertEquals("It was a dark and stormy night.", cleaned[1])
    }

    @Test
    fun testHyphenatedWordStitchingAcrossPages() {
        val page1 = listOf("The process was differ-")
        val page2 = listOf("ent than expected.")

        val result = TextCleaner.stitchPageText(listOf(page1, page2))

        assertEquals(1, result.size)
        assertEquals("The process was different than expected.", result[0])
    }

    @Test
    fun testSentenceBoundaryStitchingAcrossPages() {
        val page1 = listOf("It was the best of times, it was the worst of")
        val page2 = listOf("times, it was the age of wisdom.")

        val result = TextCleaner.stitchPageText(listOf(page1, page2))

        assertEquals(1, result.size)
        assertEquals("It was the best of times, it was the worst of times, it was the age of wisdom.", result[0])
    }
}
