package com.techwombat.liberates

import android.content.Context
import java.io.File

/** Persistent clean OCR text only; diagnostics remain in PersistentProbeLog. */
object OcrTextStore {
    private const val OCR_FILE_NAME = "kindle-ocr.txt"
    private lateinit var ocrFile: File

    @Synchronized
    fun initialize(context: Context) {
        if (::ocrFile.isInitialized) return
        ocrFile = File(context.filesDir, OCR_FILE_NAME)
        if (!ocrFile.exists()) ocrFile.writeText("")
    }

    @Synchronized
    fun appendPage(text: String) {
        if (!::ocrFile.isInitialized) return
        val nextPage = text.trim()
        if (nextPage.isBlank()) return
        val existing = ocrFile.readText().trimEnd()
        if (existing.isBlank()) {
            ocrFile.writeText("$nextPage\n")
            return
        }

        when {
            existing.endsWith("-") -> ocrFile.writeText(existing.dropLast(1) + nextPage + "\n")
            !endsSentence(existing) || nextPage.firstOrNull()?.isLowerCase() == true -> {
                ocrFile.writeText(existing + " " + nextPage + "\n")
            }
            else -> ocrFile.writeText(existing + "\n\n" + nextPage + "\n")
        }
    }

    private fun endsSentence(text: String): Boolean {
        val finalCharacter = text.trimEnd().trimEnd('"', '”', '’', ')', ']').lastOrNull()
        return finalCharacter != null && finalCharacter in ".!?…"
    }

    @Synchronized
    fun clear() {
        if (::ocrFile.isInitialized) ocrFile.writeText("")
    }

    @Synchronized
    fun file(): File {
        check(::ocrFile.isInitialized) { "OcrTextStore must be initialized before use." }
        return ocrFile
    }
}
