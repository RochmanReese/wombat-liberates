package com.techwombat.liberates

import org.junit.Assert.assertEquals
import org.junit.Test

class AiReviewSelectionTest {
    private val doubtful = setOf("er", "xqz")

    @Test
    fun `only sentences with doubtful words are chosen and corrections go back in place`() {
        val chunks = listOf(
            "It was quiet. She took er bag. Then she left.",
            "[[image:img-0001.jpg]]\n\nAll fine here.\n\nA xqz word! And more.",
        )
        val pieces = AiReviewSelection.select(chunks) { it in doubtful }
        assertEquals(listOf("She took er bag.", "A xqz word!"), pieces.map { it.text })
        val merged = AiReviewSelection.apply(chunks, pieces, listOf("She took her bag.", "A quiz word!"))
        assertEquals("It was quiet. She took her bag. Then she left.", merged[0])
        assertEquals("[[image:img-0001.jpg]]\n\nAll fine here.\n\nA quiz word! And more.", merged[1])
    }

    @Test
    fun `rewrites and blank answers are not put back`() {
        val chunks = listOf("She took er bag.")
        val pieces = AiReviewSelection.select(chunks) { it in doubtful }
        val tooDifferent = "She picked up her favourite leather bag and walked out into the cold night air."
        assertEquals(chunks, AiReviewSelection.apply(chunks, pieces, listOf(tooDifferent)))
        assertEquals(chunks, AiReviewSelection.apply(chunks, pieces, listOf("")))
        assertEquals(pieces, AiReviewSelection.fromJson(AiReviewSelection.toJson(pieces)))
    }
}
