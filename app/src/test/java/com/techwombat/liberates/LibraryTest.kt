package com.techwombat.liberates

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    private val filesDir: File = Files.createTempDirectory("library-test").toFile()

    @Test
    fun `books are created, listed newest first, reopened as the same object, and deleted`() {
        val library = Library(filesDir)
        val first = library.create("First", "A. Author")
        Thread.sleep(5)
        val second = library.create("Second", "")
        assertEquals(listOf("Second", "First"), library.books().map { it.title })
        assertSame(first, library.open(first.key))
        assertEquals("A. Author", Library(filesDir).open(first.key)!!.author)

        library.delete(second.key)
        assertEquals(listOf("First"), library.books().map { it.title })
        assertNull(library.open(second.key))
    }

    @Test
    fun `test scan is separate from the library`() {
        val library = Library(filesDir)
        library.test().raw.appendPage("Test page.")
        assertTrue(library.books().isEmpty())
        assertTrue(library.test().isTest)
        library.delete(Workspaces.TEST)
        assertTrue(library.test().hasText())
    }

    @Test
    fun `the single book from older app versions moves into the library with its versions and title`() {
        File(filesDir, "kindle-ocr.txt").writeText("Old book text.\n")
        val oldVersions = File(filesDir, "book-versions").apply { mkdirs() }
        File(oldVersions, "workspace.json").writeText("""{"title":"Old Title"}""")

        val books = Library(filesDir).books()
        assertEquals(1, books.size)
        assertEquals("Old Title", books.single().title)
        assertEquals("Old book text.", books.single().raw.rawText())
        assertFalse(File(filesDir, "kindle-ocr.txt").exists())
        assertFalse(oldVersions.exists())
    }
}
