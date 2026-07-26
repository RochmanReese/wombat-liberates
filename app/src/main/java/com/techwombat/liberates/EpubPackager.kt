package com.techwombat.liberates

import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object EpubPackager {

    fun createEpub(title: String, author: String, chapters: List<Chapter>, outputFile: File) {
        if (outputFile.exists()) {
            outputFile.delete()
        }

        ZipOutputStream(FileOutputStream(outputFile)).use { zos ->
            // 1. mimetype (Must be first entry, STORED uncompressed)
            val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimeBytes.size.toLong()
                compressedSize = mimeBytes.size.toLong()
                crc = calculateCrc(mimeBytes)
            }
            zos.putNextEntry(mimeEntry)
            zos.write(mimeBytes)
            zos.closeEntry()

            // 2. META-INF/container.xml
            writeZipEntry(zos, "META-INF/container.xml", getContainerXml())

            // 3. OEBPS/content.opf
            writeZipEntry(zos, "OEBPS/content.opf", getContentOpf(title, author, chapters))

            // 4. OEBPS/toc.xhtml
            writeZipEntry(zos, "OEBPS/toc.xhtml", getTocXhtml(title, chapters))

            // 5. OEBPS/chapter_XX.xhtml
            chapters.forEachIndexed { index, chapter ->
                val fileName = "OEBPS/chapter_${index + 1}.xhtml"
                writeZipEntry(zos, fileName, getChapterXhtml(chapter))
            }
        }
    }

    private fun writeZipEntry(zos: ZipOutputStream, path: String, content: String) {
        val entry = ZipEntry(path)
        zos.putNextEntry(entry)
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun calculateCrc(bytes: ByteArray): Long {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }

    private fun getContainerXml(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""
    }

    private fun getContentOpf(title: String, author: String, chapters: List<Chapter>): String {
        val manifestItems = StringBuilder()
        val spineRefs = StringBuilder()

        manifestItems.append("    <item id=\"toc\" href=\"toc.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")

        chapters.forEachIndexed { i, _ ->
            val id = "chap_${i + 1}"
            val href = "chapter_${i + 1}.xhtml"
            manifestItems.append("    <item id=\"$id\" href=\"$href\" media-type=\"application/xhtml+xml\"/>\n")
            spineRefs.append("    <itemref idref=\"$id\"/>\n")
        }

        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="pub-id" version="3.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="pub-id">urn:uuid:wombat-liberates-${System.currentTimeMillis()}</dc:identifier>
    <dc:title>${escapeXml(title)}</dc:title>
    <dc:creator>${escapeXml(author)}</dc:creator>
    <dc:language>en</dc:language>
    <meta property="dcterms:modified">${java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date())}</meta>
  </metadata>
  <manifest>
$manifestItems  </manifest>
  <spine>
$spineRefs  </spine>
</package>"""
    }

    private fun getTocXhtml(title: String, chapters: List<Chapter>): String {
        val navItems = StringBuilder()
        chapters.forEachIndexed { i, chap ->
            navItems.append("      <li><a href=\"chapter_${i + 1}.xhtml\">${escapeXml(chap.title)}</a></li>\n")
        }

        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head>
  <title>${escapeXml(title)} - Table of Contents</title>
  <meta charset="utf-8"/>
</head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>${escapeXml(title)}</h1>
    <ol>
$navItems    </ol>
  </nav>
</body>
</html>"""
    }

    private fun getChapterXhtml(chapter: Chapter): String {
        val paragraphHtml = StringBuilder()
        chapter.paragraphs.forEach { p ->
            paragraphHtml.append("  <p>${escapeXml(p)}</p>\n")
        }

        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
  <title>${escapeXml(chapter.title)}</title>
  <meta charset="utf-8"/>
</head>
<body>
  <h1>${escapeXml(chapter.title)}</h1>
$paragraphHtml</body>
</html>"""
    }

    private fun escapeXml(str: String): String {
        return str
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
