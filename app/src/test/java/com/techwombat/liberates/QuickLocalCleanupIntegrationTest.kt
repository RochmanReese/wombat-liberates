package com.techwombat.liberates

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickLocalCleanupIntegrationTest {
    private val defaults = File("src/main/assets/ocr-rules.txt").readText()

    @Test
    fun `saved custom rule affects local cleanup while raw OCR stays unchanged`() {
        val directory = Files.createTempDirectory("quick-local-cleanup-test").toFile()
        val rulesFile = File(directory, "ocr-rules.txt")
        val rawFile = File(directory, "kindle-ocr.txt")
        val cleanedFile = File(directory, "kindle-ocr-corrected.txt")
        val customRule = OcrLiteralRule("teh", "the")
        val rawText = "As | told teh Councilman"

        OcrRulesStore.writeCustomRules(rulesFile, defaults, listOf(customRule))
        rawFile.writeText(rawText)
        val reloadedCustomRules = OcrRulesStore.customRules(rulesFile, defaults)
        val parsedRules = OcrRulesParser.parse(rulesFile.readText())
        assertTrue(parsedRules.errors.toString(), parsedRules.isValid)

        cleanedFile.writeText(OcrCleanup.clean(rawFile.readText(), parsedRules.rules))

        assertEquals(listOf(customRule), reloadedCustomRules)
        assertEquals(rawText, rawFile.readText())
        assertEquals("As I told the Councilman", cleanedFile.readText())
    }
}
