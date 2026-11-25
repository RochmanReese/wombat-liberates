package com.techwombat.liberates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pattern of a real scan (25 Nov) that lost its first chapters from the EPUB: short junk "headings" from the
 * back matter ("Down", "Plan", "Path") were found inside ordinary words near the front ("explained", "sympathy",
 * "downstairs"), so the "contents page" ran deep into the story and was skipped. Also "Chapter Three" was glued
 * onto the previous paragraph because OCR lost the full stop before the page break.
 */
class ContentsSafetyTest {
    private val numbers = listOf("One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight")

    private fun prose(chapter: Int, n: Int) = List(n) {
        "In chapter $chapter, part $it, she explained the plan downstairs with real sympathy, and they walked the path down to the river."
    }

    private fun book(): List<String> {
        val front = listOf(
            "THE LONG ROAD A Novel", "Copyright 2026 The Author. All rights reserved.",
            "Table of Contents", "Map of the World",
        ) + numbers.map { "Chapter $it" } + listOf("Notes on the Map")
        val body = mutableListOf(Illustrations.marker("img-0001.jpg"))
        numbers.forEachIndexed { i, number ->
            val heading = "Chapter $number"
            if (number == "Three") {
                // Glued onto the end of the previous paragraph.
                body[body.lastIndex] = body.last() + " “Right, everyone, let's ride out” $heading"
            } else {
                body += heading
            }
            body += "(Monday, June 2.)"
            body += prose(i + 1, 12)
        }
        val back = listOf("Notes on the Map") + prose(99, 2) +
            listOf("Down", "Book Four - The Far Shore", "Plan", "Book Seven - Winter Roads", "Path", "Book Twelve - Home Again") +
            // A surprise epilogue, not in the printed contents, glued onto the author's website line.
            listOf("Visit my website and blog: http:// www.example-author.com/ Epilogue",
                "Post Credit Scene (Monday, June 2.)") + prose(100, 6)
        return front + body + back
    }

    @Test
    fun `no story text is skipped as a contents page`() {
        val result = ChapterDetector.detect(book())
        val skipped = result.contentsPage?.map { result.paragraphs[it] }.orEmpty()
        assertTrue("skipped story text: $skipped", skipped.none { it.startsWith("In chapter") || Illustrations.isMarker(it) })
        assertFalse("map picture skipped", skipped.any { Illustrations.isMarker(it) })
    }

    @Test
    fun `every chapter is found, including the glued Chapter Three, and no junk words`() {
        val result = ChapterDetector.detect(book())
        val titles = result.chapters.map { it.title }
        numbers.forEach { assertTrue("missing Chapter $it in $titles", titles.any { t -> t.startsWith("Chapter $it") }) }
        listOf("Down", "Plan", "Path").forEach { assertFalse("junk chapter $it in $titles", it in titles) }
        assertTrue("missing Epilogue in $titles", titles.any { it.startsWith("Epilogue") })
        assertTrue("Epilogue must be the last chapter: $titles", titles.last().startsWith("Epilogue"))
    }

    @Test
    fun `glued heading is split off and the sentence before it is kept`() {
        val paragraphs = ChapterDetector.detect(book()).paragraphs
        assertTrue(paragraphs.any { it.endsWith("let's ride out”") })
        assertTrue("Chapter Three" in paragraphs)
        assertTrue("Epilogue" in paragraphs)
        assertTrue(paragraphs.any { it.endsWith("www.example-author.com/") })
        // The server cleanup tends to move the glued heading onto its own line within the paragraph.
        val afterCleanup = ChapterDetector.detect(listOf("Some earlier line.\nVisit my website and blog: http://www.example-author.com/\nEpilogue", "Post Credit Scene") + prose(100, 3)).paragraphs
        assertTrue("Epilogue" in afterCleanup)
    }

    @Test
    fun `every story paragraph reaches the EPUB`() {
        val text = book().joinToString("\n\n")
        val epub = java.io.ByteArrayOutputStream().also { EpubWriter.write(it, text, "T", "A") }.toByteArray()
        val all = StringBuilder()
        java.util.zip.ZipInputStream(epub.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.filter { it.name.startsWith("OEBPS/chapters/") }
                .forEach { all.append(String(zip.readBytes())) }
        }
        (1..numbers.size).forEach { chapter ->
            (0 until 12).forEach { part ->
                assertTrue("chapter $chapter part $part missing", all.contains("In chapter $chapter, part $part,"))
            }
        }
    }

    @Test
    fun `a page that starts with a heading is never joined onto the previous page`() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val files = OcrTextFiles(dir, "kindle-ocr")
            files.appendPage("“Right, everyone, let's ride out”")
            files.appendPage("Chapter Three\n\n(Monday, June 2.)\n\nThe road was long.")
            assertTrue(files.rawText().contains("ride out”\n\nChapter Three"))
            assertTrue(ChapterDetector.startsWithHeading("CHAPTER\n\nTWO\n\nText."))
            assertFalse(ChapterDetector.startsWithHeading("and then he left the room."))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a sentence ending in a chapter reference with its full stop is left alone`() {
        val paragraphs = listOf("As we saw before, the answer is explained in Chapter Three.", "Next paragraph here.")
        assertEquals(paragraphs, ChapterDetector.detect(paragraphs).paragraphs)
        // Lower-case "epilogue" at the end of a sentence is a word, not a heading.
        val sentence = listOf("She skipped ahead and read the whole of the epilogue", "Next paragraph here.")
        assertEquals(sentence, ChapterDetector.detect(sentence).paragraphs)
    }
}
