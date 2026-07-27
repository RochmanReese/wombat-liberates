package com.techwombat.liberates

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrRulesStoreTest {
    private val defaultRules = File("src/main/assets/ocr-rules.txt").readText()

    @Test
    fun `bundled defaults contain representative rules in source order`() {
        assertTrue(defaultRules.contains("4| => “I"))
        assertTrue(defaultRules.contains("yOur => your"))
        assertTrue(defaultRules.indexOf("4| => “I") < defaultRules.indexOf("yOur => your"))
    }

    @Test
    fun `defaults seed an absent app private rules file exactly`() {
        val directory = Files.createTempDirectory("ocr-rules-test").toFile()
        val rulesFile = File(directory, "ocr-rules.txt")

        OcrRulesStore.seedDefaultsIfMissing(rulesFile, defaultRules)

        assertEquals(defaultRules, rulesFile.readText())
    }

    @Test
    fun `seeding preserves existing custom rules across later loads`() {
        val directory = Files.createTempDirectory("ocr-rules-test").toFile()
        val rulesFile = File(directory, "ocr-rules.txt")
        rulesFile.writeText("custom => replacement\n")

        OcrRulesStore.seedDefaultsIfMissing(rulesFile, defaultRules)

        assertEquals("custom => replacement\n", rulesFile.readText())
    }

    @Test
    fun `custom rules are appended after defaults and can be removed independently`() {
        val directory = Files.createTempDirectory("ocr-rules-test").toFile()
        val rulesFile = File(directory, "ocr-rules.txt")
        val customRules = listOf(OcrLiteralRule("custom", "replacement"), OcrLiteralRule("remove", ""))

        OcrRulesStore.writeCustomRules(rulesFile, defaultRules, customRules)

        assertEquals(customRules, OcrRulesStore.customRules(rulesFile, defaultRules))
        assertTrue(rulesFile.readText().startsWith(defaultRules.trimEnd()))
    }

    @Test
    fun `reset restores defaults without changing unrelated OCR files`() {
        val directory = Files.createTempDirectory("ocr-rules-test").toFile()
        val rulesFile = File(directory, "ocr-rules.txt").apply { writeText("custom => replacement\n") }
        val rawOcrFile = File(directory, "kindle-ocr.txt").apply { writeText("raw OCR\n") }
        val cleanedOcrFile = File(directory, "kindle-ocr-corrected.txt").apply { writeText("cleaned OCR\n") }

        OcrRulesStore.resetToDefaults(rulesFile, defaultRules)

        assertEquals(defaultRules, rulesFile.readText())
        assertEquals("raw OCR\n", rawOcrFile.readText())
        assertEquals("cleaned OCR\n", cleanedOcrFile.readText())
    }
}
