package com.techwombat.liberates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class EpubPackagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testEpubCreationProducesValidZipEntries() {
        val testFile = tempFolder.newFile("test_book.epub")

        val chapter1 = Chapter(1, "Chapter 1", listOf("Paragraph 1", "Paragraph 2"))
        val chapter2 = Chapter(2, "Chapter 2", listOf("Paragraph 3"))
        val chapters = listOf(chapter1, chapter2)

        EpubPackager.createEpub("Test Book Title", "Test Author", chapters, testFile)

        assertTrue("EPUB file must exist and be non-empty", testFile.exists() && testFile.length() > 0)

        ZipFile(testFile).use { zip ->
            val entryNames = zip.entries().asSequence().map { it.name }.toList()

            assertTrue("Zip must contain mimetype", entryNames.contains("mimetype"))
            assertTrue("Zip must contain META-INF/container.xml", entryNames.contains("META-INF/container.xml"))
            assertTrue("Zip must contain OEBPS/content.opf", entryNames.contains("OEBPS/content.opf"))
            assertTrue("Zip must contain OEBPS/toc.xhtml", entryNames.contains("OEBPS/toc.xhtml"))
            assertTrue("Zip must contain OEBPS/chapter_1.xhtml", entryNames.contains("OEBPS/chapter_1.xhtml"))
            assertTrue("Zip must contain OEBPS/chapter_2.xhtml", entryNames.contains("OEBPS/chapter_2.xhtml"))

            // Verify mimetype is uncompressed
            val mimeEntry = zip.getEntry("mimetype")
            assertEquals("mimetype entry must be STORED uncompressed", 0, mimeEntry.method)
        }
    }
}
