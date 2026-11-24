package com.techwombat.liberates

import com.techwombat.liberates.WordDiff.Kind
import com.techwombat.liberates.WordDiff.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordDiffTest {
    @Test
    fun `identical text has no changes`() {
        val pieces = WordDiff.diff("The cat sat.", "The cat sat.")
        assertTrue(WordDiff.summarize(pieces).unchanged)
        assertTrue(WordDiff.changedParagraphs(pieces).isEmpty())
    }

    @Test
    fun `single word fix shows as removed then added`() {
        val pieces = WordDiff.diff("As | told teh Councilman", "As I told the Councilman")
        assertEquals(
            listOf(
                Piece(Kind.SAME, "As"),
                Piece(Kind.REMOVED, "|"),
                Piece(Kind.ADDED, "I"),
                Piece(Kind.SAME, "told"),
                Piece(Kind.REMOVED, "teh"),
                Piece(Kind.ADDED, "the"),
                Piece(Kind.SAME, "Councilman"),
            ),
            pieces,
        )
        assertEquals(WordDiff.Summary(removedWords = 2, addedWords = 2, changeRegions = 2), WordDiff.summarize(pieces))
    }

    @Test
    fun `only paragraphs with changes are kept`() {
        val before = "First paragraph is fine.\n\nSecond has a typo teh.\n\nThird is fine too."
        val after = "First paragraph is fine.\n\nSecond has a typo the.\n\nThird is fine too."
        val paragraphs = WordDiff.changedParagraphs(WordDiff.diff(before, after))
        assertEquals(1, paragraphs.size)
        assertEquals(
            listOf(Piece(Kind.SAME, "Second has a typo"), Piece(Kind.REMOVED, "teh."), Piece(Kind.ADDED, "the.")),
            paragraphs.single(),
        )
    }

    @Test
    fun `dropped sentence is counted as removed words`() {
        val summary = WordDiff.summarize(WordDiff.diff("One two. Three four five.", "One two."))
        assertEquals(3, summary.removedWords)
        assertEquals(0, summary.addedWords)
    }
}
