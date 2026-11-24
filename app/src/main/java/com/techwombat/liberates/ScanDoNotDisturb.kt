package com.techwombat.liberates

import android.app.NotificationManager
import android.content.Context

/**
 * Optionally turns on Do Not Disturb for the length of a scan and puts it back afterwards.
 * Uses the user's own Do Not Disturb rules ("priority only"), so starred contacts or alarms they
 * allow still come through. Never touches Do Not Disturb the user had already turned on.
 */
object ScanDoNotDisturb {
    private const val PREFERENCES_NAME = "scan_settings"
    private const val SET_BY_SCAN = "dnd_set_by_scan"
    private const val PREVIOUS_FILTER = "dnd_previous_filter"

    fun hasPermission(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    /** Turns Do Not Disturb on if the setting is on, permission is granted and it is currently off. */
    fun enableForScan(context: Context): Boolean {
        if (!ScanSettings.doNotDisturbWhileScanning(context) || !hasPermission(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val current = manager.currentInterruptionFilter
        if (current != NotificationManager.INTERRUPTION_FILTER_ALL) return false // already on: leave it alone
        return runCatching {
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            preferences(context).edit().putBoolean(SET_BY_SCAN, true).putInt(PREVIOUS_FILTER, current).apply()
            true
        }.getOrDefault(false)
    }

    /** Restores Do Not Disturb if this app turned it on and the user has not changed it since. */
    fun restoreAfterScan(context: Context) {
        val preferences = preferences(context)
        if (!preferences.getBoolean(SET_BY_SCAN, false)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching {
            if (manager != null && hasPermission(context) &&
                manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
            ) {
                manager.setInterruptionFilter(preferences.getInt(PREVIOUS_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL))
            }
        }
        preferences.edit().remove(SET_BY_SCAN).remove(PREVIOUS_FILTER).apply()
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
