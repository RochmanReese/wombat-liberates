package com.techwombat.liberates

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterDetectorTest {
    private fun body(n: Int) = List(n) { "This is an ordinary paragraph of the story, number $it, which ends with a full stop." }

    @Test
    fun `chapters are matched to the printed contents page, which is then left out`() {
        val paragraphs = listOf("The Last Ranger", "Copyright 2024. All rights reserved.", "Contents",
            "The Storm 1", "Into the Woods 15", "A Long Night 32", "Morning Comes 47") +
            listOf("The Storm") + body(5) + listOf("Into the Woods") + body(5) + listOf("A Long Night") + body(5) +
            listOf("Morning Comes") + body(5)
        val result = ChapterDetector.detect(paragraphs)
        assertEquals(ChapterDetector.Source.CONTENTS_PAGE, result.source)
        assertEquals(listOf("The Storm", "Into the Woods", "A Long Night", "Morning Comes"), result.chapters.map { it.title })
        assertEquals(2..6, result.contentsPage)
    }

    @Test
    fun `chapter headings with a title on the next line are joined`() {
        val paragraphs = body(3) + listOf("Chapter 1", "The Beginning") + body(4) + listOf("Chapter Two", "Smoke") + body(4) +
            listOf("Epilogue") + body(2)
        val result = ChapterDetector.detect(paragraphs)
        assertEquals(ChapterDetector.Source.HEADINGS, result.source)
        assertEquals(listOf("Chapter 1: The Beginning", "Chapter Two: Smoke", "Epilogue"), result.chapters.map { it.title })
        assertEquals(2, result.chapters.first().headingParagraphs)
    }

    @Test
    fun `bare numbers count only when there are several`() {
        val numbered = listOf("1") + body(3) + listOf("2") + body(3) + listOf("3") + body(3)
        assertEquals(listOf("1", "2", "3"), ChapterDetector.detect(numbered).chapters.map { it.title })
        val oneNumber = body(3) + listOf("42") + body(3)
        assertEquals(ChapterDetector.Source.NONE, ChapterDetector.detect(oneNumber).source)
    }

    @Test
    fun `an EPUB built from a book with a contents page has real chapters and no printed contents`() {
        val text = (listOf("Contents", "Arrival 1", "Departure 9", "Return 20") + listOf("Arrival") + body(2) +
            listOf("Departure") + body(2) + listOf("Return") + body(2)).joinToString("\n\n")
        val epub = java.io.ByteArrayOutputStream().also { EpubWriter.write(it, text, "T", "A") }.toByteArray()
        val nav = java.util.zip.ZipInputStream(epub.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "OEBPS/nav.xhtml" }.let { String(zip.readBytes()) }
        }
        listOf("Arrival", "Departure", "Return").forEach { assert(nav.contains(">$it<")) { "missing $it in $nav" } }
        assert(!nav.contains("Front matter")) { "contents page should not become front matter: $nav" }
    }

    @Test
    fun `a heading split into CHAPTER and TWELVE is one heading, and missed ones are added`() {
        val paragraphs = listOf("Contents", "Chapter One", "Chapter Two", "Chapter Three", "Chapter Four") +
            listOf("CHAPTER ONE") + body(3) + listOf("CHAPTER TWO") + body(3) + listOf("CHAPTER THREE") + body(3) +
            listOf("CHAPTER", "FOUR") + body(3)
        val result = ChapterDetector.detect(paragraphs)
        assertEquals(listOf("Chapter One", "Chapter Two", "Chapter Three", "Chapter Four"), result.chapters.map { it.title })
        assertEquals(2, result.chapters.last().headingParagraphs)
    }

    @Test
    fun `capital headings glued to the first sentence are split off, with the contents page's spelling`() {
        val contents = listOf("4", "Pride & Shame", "5", "Treachery 6", "From Below", "7", "Skin-Walker 8")
        val paragraphs = listOf("The Book") + contents + body(30) +
            listOf("PRIDE && SHAME Her bow thwacked.") + body(3) +
            listOf("TREACHERY She heard them.") + body(3) +
            listOf("He said it all. FROM BELOW As the soldiers charged.") + body(3) +
            listOf("PART", "SMOKE ON THE FRONTIER SKIN-WALKER Early the next morning.") + body(3)
        val result = ChapterDetector.detect(paragraphs, dictionary = null)
        assertEquals(listOf("Pride & Shame", "Treachery", "From Below", "Skin-Walker"), result.chapters.map { it.title })
        val first = result.chapters.first()
        assertEquals("PRIDE && SHAME", result.paragraphs[first.paragraph])
        assertEquals("Her bow thwacked.", result.paragraphs[first.paragraph + 1])
        // Text before an inline heading stays with the previous chapter.
        assertEquals("He said it all.", result.paragraphs[result.chapters[2].paragraph - 1])
        assertEquals("SMOKE ON THE FRONTIER", result.paragraphs[result.chapters[3].paragraph - 1])
    }

    @Test
    fun `a chapter's lost decorative first letter is put back`() {
        val dictionary = SpellingDictionary.load(
            java.io.File("src/main/assets/spelling/words-en.txt").inputStream(),
            java.io.File("src/main/assets/spelling/bigrams-en.txt").inputStream(),
        )
        val contents = listOf("Arrival 1", "Departure 9", "Return 20", "Leaving 31")
        val names = List(5) { "Leiyn walked on, and Leiyn smiled." }
        val paragraphs = contents + names + body(30) +
            listOf("ARRIVAL s the sun set, she rode.") + body(2) +
            listOf("DEPARTURE eiyn rode hard.") + body(2) +
            listOf("RETURN t wasn't long.") + body(2) +
            listOf("LEAVING e don't have long.") + body(2)
        val result = ChapterDetector.detect(paragraphs, dictionary)
        val openings = result.chapters.map { result.paragraphs[it.paragraph + 1] }
        assertEquals(listOf("As the sun set, she rode.", "Leiyn rode hard.", "It wasn't long.", "We don't have long."), openings)
    }
}
