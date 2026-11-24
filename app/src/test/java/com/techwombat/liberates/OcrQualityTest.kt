package com.techwombat.liberates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrQualityTest {
    @Test
    fun `typical OCR errors are flagged`() {
        listOf("|", "yOur", "c0me", "t1me", "©", "x", "wor|d").forEach { assertTrue(it, OcrQuality.isSuspicious(it)) }
    }

    @Test
    fun `ordinary words, numbers and initials are not flagged`() {
        listOf("the", "“Hello,”", "I", "a", "1984", "21st", "1920s", "J.", "McDonald", "WillCo", "DeVas", "don’t", "—", "well-known", "NASA")
            .forEach { assertFalse(it, OcrQuality.isSuspicious(it)) }
    }

    @Test
    fun `clean prose is rated good`() {
        val text = "It was a bright cold day in April, and the clocks were striking thirteen. ".repeat(20)
        assertEquals(OcrQuality.Level.GOOD, OcrQuality.assess(text).level)
    }

    @Test
    fun `messy OCR is rated poor with the most common problems listed first`() {
        val text = "| went to tHe shop and | saw c0me things there today ".repeat(10)
        val report = OcrQuality.assess(text)
        assertEquals(OcrQuality.Level.POOR, report.level)
        assertEquals("|" to 20, report.examples.first())
        assertTrue(OcrQuality.describe(report).contains("Ollama cleanup recommended"))
    }
}
