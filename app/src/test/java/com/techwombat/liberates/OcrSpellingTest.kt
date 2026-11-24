package com.techwombat.liberates

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class OcrSpellingTest {
    companion object {
        lateinit var dictionary: SpellingDictionary

        @BeforeClass
        @JvmStatic
        fun load() {
            dictionary = SpellingDictionary.load(
                File("src/main/assets/spelling/words-en.txt").inputStream(),
                File("src/main/assets/spelling/bigrams-en.txt").inputStream(),
            )
        }
    }

    private fun correct(text: String, book: String = text) = OcrSpelling(dictionary, book).correct(text)

    @Test
    fun `typical OCR slips are fixed`() {
        assertEquals("She stood there with her bag.", correct("She stOod there with her bag."))
        assertEquals("He will be late and so will all of them.", correct("He wil be late and so will al of them."))
        assertEquals("A small dog, and the governments of the world managed it.", correct("A smal dog, and the govemments of the world mnanaged it."))
        assertEquals("I'm sure I'll go and I don't mind.", correct("Im sure IIl go and I dont mind."))
        assertEquals("All the Blood.", correct("AIl the BIood."))
    }

    @Test
    fun `er becomes her where the words on both sides want her`() {
        assertEquals("She picked up her bag and left.", correct("She picked up er bag and left."))
    }

    @Test
    fun `broken words are joined`() {
        assertEquals("The building was relatively new.", correct("The buil ding was rela tively new."))
        assertEquals("a way out", correct("a way out")) // two real words stay apart
    }

    @Test
    fun `names, fantasy words and real words are left alone`() {
        val book = "Inaxidor raised his staff. The elf nodded. Inaxidor smiled. The elf ran. Inaxidor left. The elf hid. " +
            "Ngo said hello. A drider crawled past. She gave him a hug. He sat on the warchiefs' throne. Al Gore spoke."
        assertEquals(book, correct(book))
    }

    @Test
    fun `addresses and hyphenated pieces are left alone`() {
        assertEquals("Write to me@gmail.com or see re-enter now.", correct("Write to me@gmail.com or see re-enter now."))
    }

    @Test
    fun `a misreading the book itself shows the right spelling of is fixed`() {
        val book = "Hitomi waved. Hitomi laughed. Hitomi ran. Hitomi sat. Hitomi slept. Then Hitomni stopped."
        assertTrue(correct(book).endsWith("Then Hitomi stopped."))
    }

    @Test
    fun `words left for AI review are the doubtful ones`() {
        val spelling = OcrSpelling(dictionary, "The elf ran. The elf hid. The elf sat. Inaxidor came. Inaxidor went. Inaxidor stayed.")
        assertTrue(spelling.needsReview("er"))
        assertTrue(spelling.needsReview("xqzt"))
        assertFalse(spelling.needsReview("elf")) // used regularly in this book
        assertFalse(spelling.needsReview("Inaxidor")) // a name the book capitalises
        assertFalse(spelling.needsReview("her"))
    }

    @Test
    fun `the book's own word beats a more common English one`() {
        val book = "The stallion ran. ".repeat(16) + "The station."
        assertEquals("The stallion seemed startled.", correct("The stalion seemed startled.", book + " The stalion seemed startled."))
    }
}
