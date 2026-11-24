package com.techwombat.liberates

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubWriterTest {
    @Test
    fun generatedPublicationXmlStartsAtFirstByteAndIsWellFormed() {
        val epub = ByteArrayOutputStream().also {
            EpubWriter.write(
                output = it,
                sourceText = "CHAPTER ONE\n\nText with & <characters>.\n\nCHAPTER TWO\n\nMore text.",
                title = "Elliot's Tale",
                author = "A. Writer",
            )
        }.toByteArray()
        val entries = zipEntries(epub)
        val xmlPaths = entries.keys.filter { it.endsWith(".xhtml") || it.endsWith(".opf") || it.endsWith(".xml") || it.endsWith(".ncx") }
        val ncx = String(requireNotNull(entries["OEBPS/toc.ncx"]))
        assertTrue(ncx.contains("<text>CHAPTER ONE</text>") && ncx.contains("chapters/chapter-002.xhtml"))
        val opf = String(requireNotNull(entries["OEBPS/content.opf"]))
        assertTrue(opf.contains("media-type=\"application/x-dtbncx+xml\"") && opf.contains("<spine toc=\"ncx\">"))

        assertTrue(xmlPaths.isNotEmpty())
        xmlPaths.forEach { path ->
            val bytes = requireNotNull(entries[path])
            assertEquals("$path must begin directly with '<'", '<'.code.toByte(), bytes.first())
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(bytes))
        }
    }

    private fun zipEntries(epub: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(epub)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
                zip.closeEntry()
            }
        }
    }
}
