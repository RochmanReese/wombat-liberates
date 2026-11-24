package com.techwombat.liberates

import android.content.Context

/** Scan preferences shared by the main screen and the scanning service. */
object ScanSettings {
    private const val PREFERENCES_NAME = "scan_settings"
    private const val TABLE_MODE = "table_mode"
    private const val SAVE_ILLUSTRATIONS = "save_illustrations"
    private const val DND_WHILE_SCANNING = "dnd_while_scanning"

    /** Turn on Do Not Disturb for the length of each scan (off by default; needs the user's permission). */
    fun doNotDisturbWhileScanning(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getBoolean(DND_WHILE_SCANNING, false)

    fun setDoNotDisturbWhileScanning(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putBoolean(DND_WHILE_SCANNING, enabled).apply()
    }

    /** Save pictures found while scanning into the book (on by default). */
    fun saveIllustrations(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getBoolean(SAVE_ILLUSTRATIONS, true)

    fun setSaveIllustrations(context: Context, save: Boolean) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putBoolean(SAVE_ILLUSTRATIONS, save).apply()
    }

    /** What to do with pages that look like tables. */
    enum class TableMode { ASK, KEEP, SKIP }

    fun tableMode(context: Context): TableMode =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).getString(TABLE_MODE, null)
            ?.let { runCatching { TableMode.valueOf(it) }.getOrNull() } ?: TableMode.ASK

    fun setTableMode(context: Context, mode: TableMode) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit().putString(TABLE_MODE, mode.name).apply()
    }
}
