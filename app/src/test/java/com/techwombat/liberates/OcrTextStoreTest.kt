package com.techwombat.liberates

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OcrTextStoreTest {
    @Before
    fun setUp() {
        OcrTextStore.initialize(Files.createTempDirectory("ocr-text-store-test").toFile())
        OcrTextStore.clear()
    }

    @Test
    fun `first page is written as is`() {
        OcrTextStore.appendPage("  Chapter One.  ")
        assertEquals("Chapter One.\n", OcrTextStore.rawFile().readText())
    }

    @Test
    fun `hyphenated page break is joined without the hyphen`() {
        OcrTextStore.appendPage("The quick brown fox jum-")
        OcrTextStore.appendPage("ped over the dog.")
        assertEquals("The quick brown fox jumped over the dog.\n", OcrTextStore.rawFile().readText())
    }

    @Test
    fun `unfinished sentence continues with a space`() {
        OcrTextStore.appendPage("She walked into the")
        OcrTextStore.appendPage("Room and sat down.")
        assertEquals("She walked into the Room and sat down.\n", OcrTextStore.rawFile().readText())
    }

    @Test
    fun `lowercase continuation after full stop joins with a space`() {
        OcrTextStore.appendPage("It was 3 p.m.")
        OcrTextStore.appendPage("and raining.")
        assertEquals("It was 3 p.m. and raining.\n", OcrTextStore.rawFile().readText())
    }

    @Test
    fun `finished sentence starts a new paragraph, including after closing quotes`() {
        OcrTextStore.appendPage("“We’re done here,” she said. “Go home.”")
        OcrTextStore.appendPage("The next morning was cold.")
        assertEquals(
            "“We’re done here,” she said. “Go home.”\n\nThe next morning was cold.\n",
            OcrTextStore.rawFile().readText(),
        )
    }

    @Test
    fun `last page text is remembered and cleared`() {
        OcrTextStore.appendPage("First page.")
        OcrTextStore.appendPage("Second page.")
        assertEquals("Second page.", OcrTextStore.lastPageText())
        assertTrue(OcrTextStore.hasRawText())
        OcrTextStore.clear()
        assertEquals("", OcrTextStore.lastPageText())
        assertFalse(OcrTextStore.hasRawText())
    }

    @Test
    fun `test scan text is kept separate from the book scan`() {
        OcrTextStore.test.clear()
        OcrTextStore.appendPage("Book page.")
        OcrTextStore.test.appendPage("Test page.")
        assertEquals("Book page.", OcrTextStore.rawText())
        assertEquals("Test page.", OcrTextStore.test.rawText())
        OcrTextStore.test.clear()
        assertEquals("Book page.", OcrTextStore.rawText())
    }
}
