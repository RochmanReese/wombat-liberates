package com.techwombat.liberates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KindleChromeTest {
    @Test
    fun `footer and toolbar lines seen in real scan logs are chrome`() {
        listOf("87%", "87/%", "18 mins left in book", "2 hrs 5 mins left in chapter", "Z hrs A7 mins left in book", "AA mins left in book", "Ahrs 5 minsleft in book", "Page 375 of 376 • 100%",
            "Page 12 of 300", "Location 1 of 5167 0%", "Page 3 of 3 76 • 2%", "Page 6 of 517 e 1%", "Location 1234 of 5678", "Loc 1234", "Learning reading speed", "Back to 249", "Aa")
            .forEach { assertTrue(it, KindleChrome.isChromeLine(it)) }
    }

    @Test
    fun `book text is not chrome`() {
        listOf("+10% Haste to range attacks.", "She was 87% sure.", "Chapter 12", "Weight: 5.2 kg", "Page after page of notes.")
            .forEach { assertFalse(it, KindleChrome.isChromeLine(it)) }
    }

    @Test
    fun `progress is read from the footer`() {
        assertEquals(87, KindleChrome.progressPercent("87/%"))
        assertEquals(100, KindleChrome.progressPercent("Page 375 of 376 • 100%"))
        assertEquals(99, KindleChrome.progressPercent("Page 375 of 376"))
        assertNull(KindleChrome.progressPercent("+10% Haste"))
    }

    @Test
    fun `strip removes chrome lines and fragments glued into sentences`() {
        val raw = "Piercing\n\n18 mins left in book leather. It has taken on the elements.\n\n87/%\n\nNext paragraph."
        assertEquals("Piercing\n\nleather. It has taken on the elements.\n\nNext paragraph.", KindleChrome.strip(raw))
    }

    @Test
    fun `end screen needs two Kindle phrases, so a novel saying before you go is safe`() {
        assertTrue(KindleChrome.isEndOfBookScreen("Review this book on Amazon and Goodreads.\nOn my profile as:\nNot on Profile v"))
        assertFalse(KindleChrome.isEndOfBookScreen("\"Before you go,\" she said, \"take this.\""))
    }
}
