package com.techwombat.liberates

import android.content.Context
import java.io.File

/** Raw OCR is immutable source data; correction output is stored separately. */
object OcrTextStore {
    private const val RAW_OCR_FILE_NAME = "kindle-ocr.txt"
    private const val CORRECTED_OCR_FILE_NAME = "kindle-ocr-corrected.txt"
    private lateinit var rawOcrFile: File
    private lateinit var correctedOcrFile: File

    @Synchronized
    fun initialize(context: Context) {
        if (::rawOcrFile.isInitialized) return
        rawOcrFile = File(context.filesDir, RAW_OCR_FILE_NAME)
        correctedOcrFile = File(context.filesDir, CORRECTED_OCR_FILE_NAME)
        if (!rawOcrFile.exists()) rawOcrFile.writeText("")
        if (!correctedOcrFile.exists()) correctedOcrFile.writeText("")
    }

    @Synchronized
    fun appendPage(text: String) {
        if (!::rawOcrFile.isInitialized) return
        val nextPage = text.trim()
        if (nextPage.isBlank()) return
        val existing = rawOcrFile.readText().trimEnd()
        if (existing.isBlank()) {
            rawOcrFile.writeText("$nextPage\n")
            return
        }

        when {
            existing.endsWith("-") -> rawOcrFile.writeText(existing.dropLast(1) + nextPage + "\n")
            !endsSentence(existing) || nextPage.firstOrNull()?.isLowerCase() == true -> {
                rawOcrFile.writeText(existing + " " + nextPage + "\n")
            }
            else -> rawOcrFile.writeText(existing + "\n\n" + nextPage + "\n")
        }
    }

    @Synchronized
    fun rawText(): String = rawOcrFile.readText().trim()

    @Synchronized
    fun beginCorrectedText() {
        if (::correctedOcrFile.isInitialized) correctedOcrFile.writeText("")
    }

    @Synchronized
    fun appendCorrectedChunk(text: String) {
        if (!::correctedOcrFile.isInitialized) return
        val chunk = text.trim()
        if (chunk.isBlank()) return
        val existing = correctedOcrFile.readText().trimEnd()
        correctedOcrFile.writeText(if (existing.isBlank()) "$chunk\n" else "$existing\n\n$chunk\n")
    }

    @Synchronized
    fun hasCorrectedText(): Boolean = ::correctedOcrFile.isInitialized && correctedOcrFile.length() > 0L

    private fun endsSentence(text: String): Boolean {
        val finalCharacter = text.trimEnd().trimEnd('"', '”', '’', ')', ']').lastOrNull()
        return finalCharacter != null && finalCharacter in ".!?…"
    }

    @Synchronized
    fun clear() {
        if (::rawOcrFile.isInitialized) rawOcrFile.writeText("")
        if (::correctedOcrFile.isInitialized) correctedOcrFile.writeText("")
    }

    @Synchronized
    fun rawFile(): File {
        check(::rawOcrFile.isInitialized) { "OcrTextStore must be initialized before use." }
        return rawOcrFile
    }

    @Synchronized
    fun correctedFile(): File {
        check(::correctedOcrFile.isInitialized) { "OcrTextStore must be initialized before use." }
        return correctedOcrFile
    }
}
