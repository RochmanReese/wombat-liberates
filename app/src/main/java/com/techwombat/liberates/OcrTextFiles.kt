package com.techwombat.liberates

import java.io.File
import java.io.RandomAccessFile

/**
 * One set of OCR text files: the raw scan and the last saved page. Raw OCR is immutable source data;
 * cleaned versions live in [ScanWorkspace]. The corrected file is only read once, to import cleanup
 * made by older app versions.
 */
class OcrTextFiles(directory: File, baseName: String) {
    private val rawOcrFile = File(directory, "$baseName.txt")
    private val correctedOcrFile = File(directory, "$baseName-corrected.txt")
    private val lastPageFile = File(directory, "$baseName-last-page.txt")

    init {
        directory.mkdirs()
        if (!rawOcrFile.exists()) rawOcrFile.writeText("")
        if (!correctedOcrFile.exists()) correctedOcrFile.writeText("")
        if (!lastPageFile.exists()) lastPageFile.writeText("")
    }

    /**
     * Appends one page without rewriting the whole file, so long scans stay fast.
     * Only the last few bytes are inspected to decide how the page joins the previous one.
     */
    @Synchronized
    fun appendPage(text: String) {
        val nextPage = text.trim()
        if (nextPage.isBlank()) return

        RandomAccessFile(rawOcrFile, "rw").use { file ->
            var end = file.length()
            while (end > 0) {
                file.seek(end - 1)
                val byte = file.read()
                if (byte == ' '.code || byte == '\n'.code || byte == '\r'.code || byte == '\t'.code) end-- else break
            }

            val separator = if (end == 0L) {
                ""
            } else {
                val tailLength = minOf(end, TAIL_BYTES).toInt()
                val tailBytes = ByteArray(tailLength)
                file.seek(end - tailLength)
                file.readFully(tailBytes)
                val tail = String(tailBytes, Charsets.UTF_8)
                when {
                    // Picture markers are always their own paragraph.
                    tail.endsWith("]]") || nextPage.startsWith("[[image:") -> "\n\n"
                    // A chapter heading starts its own paragraph even if OCR lost the previous full stop.
                    ChapterDetector.startsWithHeading(nextPage) -> "\n\n"
                    tail.endsWith("-") -> {
                        end -= 1
                        ""
                    }
                    !endsSentence(tail) || nextPage.firstOrNull()?.isLowerCase() == true -> " "
                    else -> "\n\n"
                }
            }

            file.setLength(end)
            file.seek(end)
            file.write("$separator$nextPage\n".toByteArray(Charsets.UTF_8))
        }
        lastPageFile.writeText(nextPage)
    }

    /** Text of the most recently saved page, used to avoid duplicating it when a scan continues. */
    @Synchronized
    fun lastPageText(): String = lastPageFile.readText()

    @Synchronized
    fun hasRawText(): Boolean = rawOcrFile.length() > 0L && rawText().isNotBlank()

    @Synchronized
    fun replaceRawText(text: String) {
        val rawText = text.trim()
        require(rawText.isNotBlank()) { "The selected text file is empty." }
        rawOcrFile.writeText("$rawText\n")
        correctedOcrFile.writeText("")
        lastPageFile.writeText("")
    }

    @Synchronized
    fun rawText(): String = rawOcrFile.readText().trim()

    @Synchronized
    fun hasCorrectedText(): Boolean = correctedOcrFile.length() > 0L

    private fun endsSentence(text: String): Boolean {
        val finalCharacter = text.trimEnd().trimEnd('"', '”', '’', ')', ']').lastOrNull()
        return finalCharacter != null && finalCharacter in ".!?…"
    }

    @Synchronized
    fun clear() {
        rawOcrFile.writeText("")
        correctedOcrFile.writeText("")
        lastPageFile.writeText("")
    }

    fun rawFile(): File = rawOcrFile

    /** Legacy single corrected-text file from older app versions. */
    fun correctedFile(): File = correctedOcrFile

    private companion object {
        const val TAIL_BYTES = 64L
    }
}
