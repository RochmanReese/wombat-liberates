package com.techwombat.liberates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSegmenterTest {

    @Test
    fun testChapterHeadingPatterns() {
        assertTrue(ChapterSegmenter.isChapterHeading("CHAPTER 1"))
        assertTrue(ChapterSegmenter.isChapterHeading("Chapter One"))
        assertTrue(ChapterSegmenter.isChapterHeading("PROLOGUE"))
        assertTrue(ChapterSegmenter.isChapterHeading("Epilogue"))
        assertTrue(ChapterSegmenter.isChapterHeading("PART IV"))
    }

    @Test
    fun testSegmentingMultiChapterText() {
        val paragraphs = listOf(
            "PROLOGUE",
            "Long ago in a distant realm...",
            "CHAPTER 1",
            "The morning sun broke through the clouds.",
            "It was going to be a long day.",
            "CHAPTER 2",
            "By evening, the rain had started."
        )

        val chapters = ChapterSegmenter.segmentIntoChapters(paragraphs)

        assertEquals(3, chapters.size)

        assertEquals("PROLOGUE", chapters[0].title)
        assertEquals(1, chapters[0].paragraphs.size)
        assertEquals("Long ago in a distant realm...", chapters[0].paragraphs[0])

        assertEquals("CHAPTER 1", chapters[1].title)
        assertEquals(2, chapters[1].paragraphs.size)

        assertEquals("CHAPTER 2", chapters[2].title)
        assertEquals(1, chapters[2].paragraphs.size)
    }

    @Test
    fun testFallbackSingleChapterWhenNoHeadingsExist() {
        val paragraphs = listOf(
            "Once upon a time in a faraway town.",
            "There lived a wise old wombat."
        )

        val chapters = ChapterSegmenter.segmentIntoChapters(paragraphs)

        assertEquals(1, chapters.size)
        assertEquals("Chapter 1", chapters[0].title)
        assertEquals(2, chapters[0].paragraphs.size)
    }
}
