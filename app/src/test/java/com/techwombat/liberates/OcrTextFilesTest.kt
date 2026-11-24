package com.techwombat.liberates

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTextFilesTest {
    private val directory = Files.createTempDirectory("ocr-text-files-test").toFile()
    private val store = OcrTextFiles(directory, "kindle-ocr")

    @Test
    fun `first page is written as is`() {
        store.appendPage("  Chapter One.  ")
        assertEquals("Chapter One.\n", store.rawFile().readText())
    }

    @Test
    fun `hyphenated page break is joined without the hyphen`() {
        store.appendPage("The quick brown fox jum-")
        store.appendPage("ped over the dog.")
        assertEquals("The quick brown fox jumped over the dog.\n", store.rawFile().readText())
    }

    @Test
    fun `unfinished sentence continues with a space`() {
        store.appendPage("She walked into the")
        store.appendPage("Room and sat down.")
        assertEquals("She walked into the Room and sat down.\n", store.rawFile().readText())
    }

    @Test
    fun `lowercase continuation after full stop joins with a space`() {
        store.appendPage("It was 3 p.m.")
        store.appendPage("and raining.")
        assertEquals("It was 3 p.m. and raining.\n", store.rawFile().readText())
    }

    @Test
    fun `finished sentence starts a new paragraph, including after closing quotes`() {
        store.appendPage("“We’re done here,” she said. “Go home.”")
        store.appendPage("The next morning was cold.")
        assertEquals(
            "“We’re done here,” she said. “Go home.”\n\nThe next morning was cold.\n",
            store.rawFile().readText(),
        )
    }

    @Test
    fun `last page text is remembered and cleared`() {
        store.appendPage("First page.")
        store.appendPage("Second page.")
        assertEquals("Second page.", store.lastPageText())
        assertTrue(store.hasRawText())
        store.clear()
        assertEquals("", store.lastPageText())
        assertFalse(store.hasRawText())
    }
}
