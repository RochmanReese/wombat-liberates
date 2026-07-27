package com.techwombat.liberates

import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object EpubWriter {
    private data class Chapter(val title: String, val paragraphs: List<String>)

    fun write(output: OutputStream, sourceText: String, title: String, author: String) {
        val chapters = chaptersFrom(sourceText)
        ZipOutputStream(output.buffered()).use { zip ->
            // EPUB requires this exact first entry to be uncompressed.
            putStored(zip, "mimetype", "application/epub+zip")
            putText(zip, "META-INF/container.xml", containerXml())
            putText(zip, "OEBPS/styles/book.css", stylesheet())
            putText(zip, "OEBPS/title.xhtml", titlePage(title, author))
            putText(zip, "OEBPS/nav.xhtml", navigation(title, chapters))
            putText(zip, "OEBPS/content.opf", packageDocument(title, author, chapters))
            chapters.forEachIndexed { index, chapter ->
                putText(zip, "OEBPS/${chapterPath(index)}", chapterDocument(title, chapter))
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

    private fun titlePage(title: String, author: String): String = xhtml(
        "$title — Title page",
        """
        <section class="title-page" epub:type="titlepage" xmlns:epub="http://www.idpf.org/2007/ops">
          <h1>${escape(title)}</h1><p class="author">${escape(author)}</p>
        </section>
        """.trimIndent(),
    )

    private fun chapterDocument(bookTitle: String, chapter: Chapter): String = xhtml(
        "$bookTitle — ${chapter.title}",
        buildString {
            appendLine("<section epub:type=\"chapter\" xmlns:epub=\"http://www.idpf.org/2007/ops\">")
            appendLine("  <h1>${escape(chapter.title)}</h1>")
            chapter.paragraphs.forEach { paragraph ->
                appendLine("  <p>${escape(paragraph).replace("\n", "<br />")}</p>")
            }
            append("</section>")
        },
    )

    /** Returns XML whose declaration is the very first byte, as required by EPUB/XHTML readers. */
    private fun xhtml(documentTitle: String, body: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<!DOCTYPE html>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"en\">\n")
        append("  <head><title>${escape(documentTitle)}</title><link rel=\"stylesheet\" type=\"text/css\" href=\"")
        append(if (documentTitle.endsWith("Title page")) "styles/book.css" else "../styles/book.css")
        append("\" /></head>\n")
        append("  <body>").append(body.trim()).append("</body>\n")
        append("</html>\n")
    }

    private fun navigation(bookTitle: String, chapters: List<Chapter>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<!DOCTYPE html>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"en\">\n")
        append("  <head><title>${escape(bookTitle)} — Contents</title><link rel=\"stylesheet\" type=\"text/css\" href=\"styles/book.css\" /></head>\n")
        append("  <body><nav epub:type=\"toc\" id=\"toc\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><h1>Contents</h1><ol>\n")
        chapters.forEachIndexed { index, chapter ->
            append("      <li><a href=\"${chapterPath(index)}\">${escape(chapter.title)}</a></li>\n")
        }
        append("    </ol></nav></body>\n")
        append("</html>\n")
    }

    private fun packageDocument(title: String, author: String, chapters: List<Chapter>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<package xmlns=\"http://www.idpf.org/2007/opf\" prefix=\"dcterms: http://purl.org/dc/terms/\" unique-identifier=\"book-id\" version=\"3.0\" xml:lang=\"en\">\n")
        append("  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
        append("    <dc:identifier id=\"book-id\">urn:uuid:${UUID.randomUUID()}</dc:identifier>\n")
        append("    <dc:title>${escape(title)}</dc:title>\n")
        append("    <dc:creator>${escape(author)}</dc:creator>\n")
        append("    <dc:language>en</dc:language>\n")
        append("    <meta property=\"dcterms:modified\">${Instant.now().toString().replace(Regex("\\.\\d+Z$"), "Z")}</meta>\n")
        append("  </metadata>\n")
        append("  <manifest>\n")
        append("    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
        append("    <item id=\"title-page\" href=\"title.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
        append("    <item id=\"style\" href=\"styles/book.css\" media-type=\"text/css\"/>\n")
        chapters.indices.forEach { index ->
            append("    <item id=\"chapter-${index + 1}\" href=\"${chapterPath(index)}\" media-type=\"application/xhtml+xml\"/>\n")
        }
        append("  </manifest>\n")
        append("  <spine>\n")
        append("    <itemref idref=\"title-page\"/>\n")
        chapters.indices.forEach { index -> append("    <itemref idref=\"chapter-${index + 1}\"/>\n") }
        append("  </spine>\n")
        append("</package>\n")
    }

    private fun stylesheet(): String = """
        @charset "UTF-8";
        body { margin: 5%; font-family: serif; line-height: 1.45; }
        h1 { margin: 2.5em 0 2em; text-align: center; font-size: 1.5em; font-variant: small-caps; page-break-before: always; break-before: page; }
        h1:first-child { page-break-before: avoid; break-before: avoid; }
        p { margin: 0 0 0.85em; text-align: justify; text-indent: 0; orphans: 2; widows: 2; }

        .title-page { margin-top: 35%; text-align: center; }
        .title-page h1 { margin: 0 0 1em; page-break-before: avoid; break-before: avoid; font-variant: normal; }
        .author { text-align: center; text-indent: 0; }
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

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
