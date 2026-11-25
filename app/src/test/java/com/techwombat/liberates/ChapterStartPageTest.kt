package com.techwombat.liberates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterStartPageTest {
    private val prose = "The rain had not stopped for three days, and the road down to the harbour was a river of mud " +
        "that sucked at the wheels of every cart. Nobody in the village could remember a spring like it."

    @Test
    fun `screens that open a chapter`() {
        assertTrue(ChapterDetector.isChapterStartPage("CHAPTER ONE\n\n$prose"))
        assertTrue(ChapterDetector.isChapterStartPage("Chapter 3: The Harbour\n\n$prose"))
        assertTrue(ChapterDetector.isChapterStartPage("Prologue\n\n$prose"))
        assertTrue(ChapterDetector.isChapterStartPage("12\n\n$prose"))
        assertTrue(ChapterDetector.isChapterStartPage("CHAPTER\n\nTWO\n\n$prose"))
        assertTrue(ChapterDetector.isChapterStartPage("${Illustrations.marker("img-0001.jpg")}\n\nChapter 1\n\n$prose"))
    }

    @Test
    fun `screens that do not open a chapter`() {
        // Ordinary page, and one mentioning a chapter mid-text.
        assertFalse(ChapterDetector.isChapterStartPage(prose))
        assertFalse(ChapterDetector.isChapterStartPage("$prose\n\nChapter 2\n\n$prose"))
        // Contents pages, including a second contents screen that starts with an entry.
        assertFalse(ChapterDetector.isChapterStartPage("Contents\n\nChapter 1 The Rain\n\nChapter 2 The Harbour\n\nChapter 3 The Storm"))
        assertFalse(ChapterDetector.isChapterStartPage("Chapter 7 The Return\n\nChapter 8 The Letter\n\nChapter 9 Home\n\nAcknowledgements"))
        // Title page and a heading with no prose yet.
        assertFalse(ChapterDetector.isChapterStartPage("THE LONG RAIN\n\nA Novel\n\nJane Writer"))
        assertFalse(ChapterDetector.isChapterStartPage("Chapter 1"))
        assertFalse(ChapterDetector.isChapterStartPage(""))
    }
}
