package com.techwombat.liberates

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IllustrationsTest {
    private val white = 0xFFFFFFFF.toInt()
    private val dark = 0xFF303030.toInt()

    /** A white 60x120 page; [fill] paints rows/columns dark. */
    private fun page(fill: (x: Int, y: Int) -> Boolean): IntArray =
        IntArray(60 * 120) { index -> if (fill(index % 60, index / 60)) dark else white }

    @Test
    fun `a large drawn area with no text is found as a picture`() {
        val pixels = page { x, y -> y in 40..80 && x in 10..50 && (x + y) % 3 == 0 }
        val pictures = IllustrationDetector.detect(pixels, 60, 120, textBoxes = emptyList())
        assertEquals(1, pictures.size)
        val box = pictures.single()
        assertTrue(box.top <= 40 && box.bottom >= 80 && box.left <= 10 && box.right >= 50)
    }

    @Test
    fun `text lines, small ornaments and blank pages are not pictures`() {
        // Dark rows that are all covered by OCR text boxes.
        val textPixels = page { _, y -> y % 6 in 0..2 }
        val boxes = (0 until 120 step 6).map { Box(0, it, 60, it + 3) }
        assertTrue(IllustrationDetector.detect(textPixels, 60, 120, boxes).isEmpty())
        // A small divider ornament.
        assertTrue(IllustrationDetector.detect(page { x, y -> y in 60..62 && x in 20..40 }, 60, 120, emptyList()).isEmpty())
        // Blank page.
        assertTrue(IllustrationDetector.detect(page { _, _ -> false }, 60, 120, emptyList()).isEmpty())
    }

    @Test
    fun `picture placeholders are placed between the text above and below`() {
        val above = listOf(OcrLine("The dragon rose.", 50, 100, 900, 140))
        val below = listOf(OcrLine("Everyone ran.", 50, 1500, 900, 1540))
        val page = PageLayout.analyze(listOf(above, below), 2000, listOf(Box(50, 300, 900, 1300)))
        assertEquals("The dragon rose.\n\n[[image:#0]]\n\nEveryone ran.", page.text)
        assertEquals(1, page.pictureCount)
        assertEquals("The dragon rose.\n\nEveryone ran.", page.wordsOnly)
    }

    @Test
    fun `AI output keeps exactly the markers that were in the input`() {
        val input = "Para one.\n\n[[image:img-0001.jpg]]\n\nPara two."
        assertEquals("Para one.\n\nPara two.\n\n[[image:img-0001.jpg]]", Illustrations.restoreMarkers(input, "Para one.\n\nPara two."))
        assertEquals("Fixed text.", Illustrations.restoreMarkers("Text.", "Fixed text.\n\n[[image:img-9999.jpg]]"))
    }

    @Test
    fun `markers never count as suspicious words`() {
        assertEquals(0, OcrQuality.assess("Fine words here.\n\n[[image:img-0001.jpg]]").suspiciousWords)
    }

    @Test
    fun `a page starting with a picture never joins the previous sentence`() {
        val store = OcrTextFiles(Files.createTempDirectory("marker-join").toFile(), "kindle-ocr")
        store.appendPage("He opened the")
        store.appendPage("[[image:img-0001.jpg]]\n\ndoor slowly.")
        assertEquals("He opened the\n\n[[image:img-0001.jpg]]\n\ndoor slowly.\n", store.rawFile().readText())
    }

    @Test
    fun `picture ids are never reused and deleting the cover clears it`() {
        val dir = Files.createTempDirectory("workspace-images").toFile()
        val workspace = ScanWorkspace("book-1", "Book", OcrTextFiles(dir, "kindle-ocr"), File(dir, "versions"))
        val first = workspace.saveIllustration(byteArrayOf(1))
        val second = workspace.saveIllustration(byteArrayOf(2))
        workspace.coverImageId = second
        workspace.deleteIllustration(second)
        assertNull(workspace.coverImageId)
        val third = workspace.saveIllustration(byteArrayOf(3))
        assertEquals(listOf("img-0001.jpg", "img-0003.jpg"), listOf(first, third))
        assertEquals(listOf(first, third), workspace.illustrationIds())
    }

    @Test
    fun `EPUB includes kept pictures, marks the cover, and drops deleted or cover markers from the text`() {
        val text = "CHAPTER ONE\n\n[[image:img-0001.jpg]]\n\nWords.\n\n[[image:img-0002.jpg]]\n\n[[image:img-0003.jpg]]"
        val images = mapOf("img-0001.jpg" to byteArrayOf(1, 2), "img-0002.jpg" to byteArrayOf(3, 4))
        val epub = ByteArrayOutputStream().also { EpubWriter.write(it, text, "T", "A", images, coverId = "img-0001.jpg") }.toByteArray()
        val entries = buildMap {
            ZipInputStream(ByteArrayInputStream(epub)).use { zip ->
                while (true) { val e = zip.nextEntry ?: break; put(e.name, zip.readBytes()) }
            }
        }
        assertTrue("OEBPS/images/img-0001.jpg" in entries && "OEBPS/images/img-0002.jpg" in entries)
        val opf = String(entries.getValue("OEBPS/content.opf"))
        assertTrue(opf.contains("""href="images/img-0001.jpg" media-type="image/jpeg" properties="cover-image""""))
        val chapter = String(entries.getValue("OEBPS/chapters/chapter-001.xhtml"))
        assertTrue(chapter.contains("""<img src="../images/img-0002.jpg""""))
        assertFalse(chapter.contains("img-0001.jpg")) // the cover is not repeated inside
        assertFalse(chapter.contains("img-0003.jpg")) // deleted picture
    }
}
