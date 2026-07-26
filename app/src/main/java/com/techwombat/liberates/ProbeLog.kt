package com.techwombat.liberates

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory only. A process restart clears both state and log. */
object ProbeLog {
    private const val MAX_ENTRIES = 250
    private val entries = ArrayDeque<String>()
    private val listeners = mutableSetOf<(String) -> Unit>()

    @Volatile
    var isActive: Boolean = false
        private set

    fun start() {
        isActive = true
        notifyListeners()
    }

    fun stop() {
        isActive = false
        notifyListeners()
    }

    @Synchronized
    fun append(eventName: String, eventText: String, sourceText: String) {
        if (!isActive) return
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        appendEntry("[$timestamp] $eventName\nevent: $eventText\nsource: $sourceText")
    }

    @Synchronized
    fun appendDiagnostic(title: String, body: String) {
        if (!isActive) return
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        appendEntry("[$timestamp] $title\n$body")
    }

    private fun appendEntry(entry: String) {
        entries.addLast(entry)
        PersistentProbeLog.append(entry)
        while (entries.size > MAX_ENTRIES) entries.removeFirst()
        notifyListeners()
    }

    @Synchronized
    fun snapshot(): String = entries.joinToString("\n\n").ifBlank { "No events logged." }

    @Synchronized
    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    @Synchronized
    private fun notifyListeners() {
        val current = snapshot()
        listeners.toList().forEach { it(current) }
    }
}
