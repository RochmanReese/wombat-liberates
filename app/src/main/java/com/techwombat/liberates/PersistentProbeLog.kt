package com.techwombat.liberates

import android.content.Context
import java.io.File

object PersistentProbeLog {
    private const val LOG_FILE_NAME = "kindle-accessibility-probe.log"
    private lateinit var logFile: File

    @Synchronized
    fun initialize(context: Context) {
        if (::logFile.isInitialized) return
        logFile = File(context.filesDir, LOG_FILE_NAME)
    }

    @Synchronized
    fun append(entry: String) {
        if (!::logFile.isInitialized) return
        logFile.appendText(entry)
        logFile.appendText("\n\n")
    }

    @Synchronized
    fun file(): File {
        check(::logFile.isInitialized) { "PersistentProbeLog must be initialized before use." }
        return logFile
    }
}
