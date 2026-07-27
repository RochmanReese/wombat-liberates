package com.techwombat.liberates

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrCleanupTest {
    private val defaultRules = OcrRulesParser.parse(
        File("src/main/assets/ocr-rules.txt").readText(),
    ).rules

    @Test
    fun `contextual pipe cleanup and default literals handle required examples`() {
        val cases = listOf(
            "As | told Councilman" to "As I told Councilman",
            "\"| believe" to "\"I believe",
            "|'m" to "I'm",
            "fel|" to "fell",
            "I'|" to "I'll",
            "4| understand" to "“I understand",
            "|I nodded" to "“I nodded",
            "yOur chance" to "your chance",
            "l'I| be there" to "I'll be there",
            "1'I| take a nap" to "I'll take a nap",
        )

        cases.forEach { (raw, expected) ->
            assertEquals(expected, OcrCleanup.clean(raw, defaultRules))
        }
    }

    @Test
    fun `literal rules run after contextual cleanup in saved order`() {
        val rules = listOf(
            OcrLiteralRule("I'l", "first"),
            OcrLiteralRule("first", "second"),
        )

        assertEquals("second", OcrCleanup.clean("I'|", rules))
    }

    @Test
    fun `literal rules are case sensitive and literal`() {
        val rules = listOf(OcrLiteralRule("yOur", "your"), OcrLiteralRule(".", "!"))

        assertEquals("your chance!", OcrCleanup.clean("yOur chance.", rules))
    }

    @Test
    fun `cleanup does not mutate the raw input`() {
        val raw = "As | told Councilman"

        val cleaned = OcrCleanup.clean(raw, emptyList())

        assertEquals("As | told Councilman", raw)
        assertEquals("As I told Councilman", cleaned)
    }
}
