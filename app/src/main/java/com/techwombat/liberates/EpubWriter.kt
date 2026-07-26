package com.techwombat.liberates

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object EpubWriter {
    private data class Chapter(val title: String, val paragraphs: List<String>)

    fun write(output: OutputStream, sourceText: String, title: String, author: String) {
        val chapters = chaptersFrom(sourceText)
        ZipOutputStream(output.buffered()).use { zip ->
            putStored(zip, "mimetype", "application/epub+zip")
            putText(zip, "META-INF/container.xml", containerXml())
            putText(zip, "OEBPS/styles/book.css", stylesheet())
            putText(zip, "OEBPS/nav.xhtml", navigation(title, chapters))
            putText(zip, "OEBPS/content.opf", packageDocument(title, author, chapters))
            chapters.forEachIndexed { index, chapter ->
                putText(zip, chapterPath(index), chapterDocument(title, chapter))
            }
        }
    }

    private fun chaptersFrom(text: String): List<Chapter> {
        val paragraphs = text.replace("\r\n", "\n")
            .split(Regex("\\n\\s*\\n"))
            .map(String::trim)
            .filter(String::isNotBlank)
        if (paragraphs.isEmpty()) error("There is no text to export.")

        val chapters = mutableListOf<Chapter>()
        var currentTitle = "Book"
        var currentParagraphs = mutableListOf<String>()
        paragraphs.forEach { paragraph ->
            if (isChapterHeading(paragraph)) {
                if (currentParagraphs.isNotEmpty()) {
                    chapters += Chapter(currentTitle, currentParagraphs)
                    currentParagraphs = mutableListOf()
                }
                currentTitle = paragraph.replace(Regex("\\s+"), " ")
            } else {
                currentParagraphs += paragraph
            }
        }
        if (currentParagraphs.isNotEmpty()) chapters += Chapter(currentTitle, currentParagraphs)
        return chapters.ifEmpty { listOf(Chapter("Book", paragraphs)) }
    }

    private fun isChapterHeading(value: String): Boolean =
        value.length <= 100 && value.matches(Regex("(?i)^chapter\\s+(?:[0-9]+|[ivxlcdm]+|[a-z][a-z -]*)[.!: -]*$"))

    private fun chapterDocument(bookTitle: String, chapter: Chapter): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en">
        <head><title>${escape(bookTitle)} — ${escape(chapter.title)}</title><link rel="stylesheet" type="text/css" href="../styles/book.css" /></head>
        <body><section epub:type="chapter" xmlns:epub="http://www.idpf.org/2007/ops"><h1>${escape(chapter.title)}</h1>
        ${chapter.paragraphs.joinToString("\n") { "<p>${escape(it).replace("\n", "<br />")}</p>" }}
        </section></body></html>
    """.trimIndent()

    private fun navigation(bookTitle: String, chapters: List<Chapter>): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en">
        <head><title>${escape(bookTitle)} — Contents</title></head>
        <body><nav epub:type="toc" id="toc" xmlns:epub="http://www.idpf.org/2007/ops"><h1>Contents</h1><ol>
        ${chapters.mapIndexed { index, chapter -> "<li><a href=\"${chapterPath(index)}\">${escape(chapter.title)}</a></li>" }.joinToString("\n")}
        </ol></nav></body></html>
    """.trimIndent()

    private fun packageDocument(title: String, author: String, chapters: List<Chapter>): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" prefix="dcterms: http://purl.org/dc/terms/" unique-identifier="book-id" version="3.0" xml:lang="en">
        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book-id">urn:uuid:${java.util.UUID.randomUUID()}</dc:identifier><dc:title>${escape(title)}</dc:title><dc:creator>${escape(author)}</dc:creator><dc:language>en</dc:language><meta property="dcterms:modified">${java.time.Instant.now().toString().replace(Regex("\\.\\d+Z$"), "Z")}</meta></metadata>
        <manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="style" href="styles/book.css" media-type="text/css"/>
        ${chapters.indices.joinToString("\n") { index -> "<item id=\"chapter-${index + 1}\" href=\"${chapterPath(index)}\" media-type=\"application/xhtml+xml\"/>" }}
        </manifest><spine>${chapters.indices.joinToString("") { index -> "<itemref idref=\"chapter-${index + 1}\"/>" }}</spine></package>
    """.trimIndent()

    private fun stylesheet(): String = """
        @charset "UTF-8";
        body { margin: 5%; font-family: serif; line-height: 1.45; }
        h1 { margin: 2.5em 0 2em; text-align: center; font-size: 1.5em; font-variant: small-caps; page-break-before: always; break-before: page; }
        h1:first-child { page-break-before: avoid; break-before: avoid; }
        p { margin: 0; text-align: justify; text-indent: 1.2em; orphans: 2; widows: 2; }
        h1 + p { text-indent: 0; }
    """.trimIndent()

    private fun containerXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>
    """.trimIndent()

    private fun chapterPath(index: Int) = "chapters/chapter-${(index + 1).toString().padStart(3, '0')}.xhtml"

    private fun putText(zip: ZipOutputStream, path: String, text: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun putStored(zip: ZipOutputStream, path: String, text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(bytes) }
        zip.putNextEntry(ZipEntry(path).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
        })
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
