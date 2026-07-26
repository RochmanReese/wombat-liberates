package com.techwombat.liberates

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Rect
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

data class CapturedPage(
    val pageNumber: Int,
    val textLines: List<String>,
    val contentHash: String,
    val packageName: String,
    val timestamp: Long = System.currentTimeMillis()
)

class KindleTextExtractorService : AccessibilityService() {

    companion object {
        private const val TAG = "WOMBAT_LIBERATES"
        private const val PREFS_NAME = "wombat_liberates_prefs"
        
        const val PREF_IS_CAPTURING = "is_capturing"
        const val PREF_AUTO_SWIPE = "auto_swipe"
        const val PREF_PAGE_COUNT = "page_count"
        const val PREF_LAST_PACKAGE = "last_package"
        const val PREF_LAST_LINE_COUNT = "last_line_count"
        const val PREF_DUMP_LOG_ENABLED = "dump_log_enabled"

        fun getPrefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        fun isCapturing(context: Context): Boolean {
            return getPrefs(context).getBoolean(PREF_IS_CAPTURING, false)
        }

        fun setCapturing(context: Context, capturing: Boolean) {
            getPrefs(context).edit().putBoolean(PREF_IS_CAPTURING, capturing).apply()
        }

        fun isAutoSwipe(context: Context): Boolean {
            return getPrefs(context).getBoolean(PREF_AUTO_SWIPE, false)
        }

        fun setAutoSwipe(context: Context, enabled: Boolean) {
            getPrefs(context).edit().putBoolean(PREF_AUTO_SWIPE, enabled).apply()
        }

        fun isDumpLogEnabled(context: Context): Boolean {
            return getPrefs(context).getBoolean(PREF_DUMP_LOG_ENABLED, false)
        }

        fun setDumpLogEnabled(context: Context, enabled: Boolean) {
            getPrefs(context).edit().putBoolean(PREF_DUMP_LOG_ENABLED, enabled).apply()
        }

        fun clearPages(context: Context) {
            getPrefs(context).edit()
                .putInt(PREF_PAGE_COUNT, 0)
                .putInt(PREF_LAST_LINE_COUNT, 0)
                .putString(PREF_LAST_PACKAGE, "None")
                .apply()
            val file = File(context.filesDir, "captured_pages.json")
            if (file.exists()) file.delete()
        }

        fun getCapturedPages(context: Context): List<CapturedPage> {
            val file = File(context.filesDir, "captured_pages.json")
            if (!file.exists()) return emptyList()
            val pages = mutableListOf<CapturedPage>()
            try {
                val jsonArray = JSONArray(file.readText())
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val linesArray = obj.getJSONArray("lines")
                    val lines = mutableListOf<String>()
                    for (j in 0 until linesArray.length()) {
                        lines.add(linesArray.getString(j))
                    }
                    pages.add(
                        CapturedPage(
                            pageNumber = obj.getInt("pageNumber"),
                            textLines = lines,
                            contentHash = obj.getString("contentHash"),
                            packageName = obj.optString("packageName", "unknown"),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading captured_pages.json", e)
            }
            return pages
        }

        fun logToFile(context: Context, message: String) {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val logFile = File(downloadsDir, "wombat_debug.log")
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                FileWriter(logFile, true).use { writer ->
                    writer.append("[$timestamp] $message\n")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to wombat_debug.log", e)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoSwipeRunnable: Runnable? = null
    private var lastDismissTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Wombat-Liberates Kindle Accessibility Service Connected!")
        logToFile(this, "SERVICE CONNECTED")
        startAutoSwipeLoop()
    }

    private fun startAutoSwipeLoop() {
        autoSwipeRunnable = object : Runnable {
            override fun run() {
                var nextDelay = 1800L
                try {
                    val capturing = isCapturing(this@KindleTextExtractorService)
                    val autoSwipe = isAutoSwipe(this@KindleTextExtractorService)

                    if (capturing && autoSwipe) {
                        dispatchSwipeGesture()
                        nextDelay = 1600L + Random.nextLong(600)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in auto-swipe loop", e)
                } finally {
                    mainHandler.postDelayed(this, nextDelay)
                }
            }
        }
        mainHandler.postDelayed(autoSwipeRunnable!!, 1800)
    }

    override fun onDestroy() {
        super.onDestroy()
        autoSwipeRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: ""

        // EXCLUDE system UI, launchers, and our own app from capture
        if (packageName.isEmpty() ||
            packageName == "com.techwombat.liberates" ||
            packageName == "com.android.systemui" ||
            packageName.contains("launcher", ignoreCase = true) ||
            packageName == "android"
        ) {
            return
        }

        getPrefs(this).edit().putString(PREF_LAST_PACKAGE, packageName).apply()

        val capturing = isCapturing(this)
        val dumpLogEnabled = isDumpLogEnabled(this)

        val rootNode = rootInActiveWindow ?: return

        if (dumpLogEnabled) {
            val sb = StringBuilder()
            sb.append("\n=== ACCESSIBILITY NODE DUMP (Pkg: $packageName, Event: ${AccessibilityEvent.eventTypeToString(event.eventType)}) ===\n")
            dumpNodeHierarchy(rootNode, 0, sb)
            sb.append("================================================================================\n")
            logToFile(this, sb.toString())
        }

        if (!capturing) return

        val extractedLines = mutableListOf<String>()
        traverseNodeTree(rootNode, extractedLines)

        if (extractedLines.isEmpty()) return

        // CHECK IF KINDLE MENU OVERLAY IS OPEN
        val isMenuOverlayVisible = extractedLines.any { line ->
            line.equals("Table of Contents", ignoreCase = true) ||
            line.equals("Reading Settings", ignoreCase = true) ||
            line.equals("Add bookmark", ignoreCase = true) ||
            line.equals("Close Book", ignoreCase = true)
        }

        if (isMenuOverlayVisible) {
            val now = System.currentTimeMillis()
            if (now - lastDismissTime > 2000) {
                lastDismissTime = now
                logToFile(this, "Kindle Menu Overlay detected! Dismissing with center tap.")
                dismissMenuOverlay()
            }
            return
        }

        val cleanBookLines = TextCleaner.cleanPageLines(extractedLines)
        if (cleanBookLines.isEmpty()) return

        val combinedContent = cleanBookLines.joinToString("\n")
        val contentHash = computeHash(combinedContent)

        val existingPages = getCapturedPages(this).toMutableList()
        val lastPage = existingPages.lastOrNull()

        if (lastPage != null && lastPage.contentHash == contentHash) {
            return
        }

        if (lastPage != null && isSimilarContent(lastPage.textLines.joinToString("\n"), combinedContent)) {
            return
        }

        val newPageNum = existingPages.size + 1
        val newPage = CapturedPage(
            pageNumber = newPageNum,
            textLines = cleanBookLines,
            contentHash = contentHash,
            packageName = packageName
        )

        existingPages.add(newPage)
        savePages(existingPages)

        getPrefs(this).edit()
            .putInt(PREF_PAGE_COUNT, newPageNum)
            .putInt(PREF_LAST_LINE_COUNT, cleanBookLines.size)
            .apply()

        logToFile(this, "CAPTURED PAGE #$newPageNum (${cleanBookLines.size} lines from $packageName)")
    }

    private fun dumpNodeHierarchy(node: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        if (node == null) return
        val indent = "  ".repeat(depth)
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val className = node.className?.toString() ?: "UnknownClass"
        val viewId = node.viewIdResourceName ?: "NoId"
        val text = node.text?.toString()?.replace("\n", "\\n") ?: ""
        val contentDesc = node.contentDescription?.toString()?.replace("\n", "\\n") ?: ""

        sb.append("$indent- [$className] id=$viewId bounds=$bounds text=\"$text\" desc=\"$contentDesc\" clickable=${node.isClickable} visible=${node.isVisibleToUser}\n")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            dumpNodeHierarchy(child, depth + 1, sb)
            child?.recycle()
        }
    }

    fun dispatchSwipeGesture() {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        val startX = width * (0.90f + Random.nextFloat() * 0.04f)
        val endX = width * (0.08f + Random.nextFloat() * 0.04f)
        val startY = height * (0.65f + Random.nextFloat() * 0.05f)
        val endY = startY + (Random.nextFloat() * 10f - 5f)

        val swipePath = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val strokeDuration = 130L + Random.nextLong(40)

        val gestureBuilder = GestureDescription.Builder()
        val stroke = GestureDescription.StrokeDescription(swipePath, 0, strokeDuration)
        gestureBuilder.addStroke(stroke)

        dispatchGesture(
            gestureBuilder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                }
            },
            mainHandler
        )
    }

    private fun dismissMenuOverlay() {
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels.toFloat() * 0.5f
        val centerY = displayMetrics.heightPixels.toFloat() * 0.5f

        val tapPath = Path().apply {
            moveTo(centerX, centerY)
        }

        val gestureBuilder = GestureDescription.Builder()
        val stroke = GestureDescription.StrokeDescription(tapPath, 0, 50)
        gestureBuilder.addStroke(stroke)

        dispatchGesture(gestureBuilder.build(), null, mainHandler)
    }

    private fun savePages(pages: List<CapturedPage>) {
        try {
            val jsonArray = JSONArray()
            for (p in pages) {
                val obj = JSONObject().apply {
                    put("pageNumber", p.pageNumber)
                    put("contentHash", p.contentHash)
                    put("packageName", p.packageName)
                    put("timestamp", p.timestamp)
                    val linesArr = JSONArray()
                    p.textLines.forEach { linesArr.put(it) }
                    put("lines", linesArr)
                }
                jsonArray.put(obj)
            }
            val file = File(filesDir, "captured_pages.json")
            file.writeText(jsonArray.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Error saving pages to disk", e)
        }
    }

    private fun traverseNodeTree(node: AccessibilityNodeInfo?, outputList: MutableList<String>) {
        if (node == null) return

        val text = node.text?.toString()?.trim()
        val contentDesc = node.contentDescription?.toString()?.trim()

        val textToUse = if (!text.isNullOrEmpty()) text else contentDesc

        if (!textToUse.isNullOrEmpty()) {
            outputList.add(textToUse)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            traverseNodeTree(child, outputList)
            child?.recycle()
        }
    }

    private fun computeHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun isSimilarContent(s1: String, s2: String): Boolean {
        if (s1.isEmpty() || s2.isEmpty()) return false
        val minLen = minOf(s1.length, s2.length).toDouble()
        val maxLen = maxOf(s1.length, s2.length).toDouble()
        if (minLen / maxLen < 0.8) return false

        var matchingChars = 0
        val checkLen = minLen.toInt()
        for (i in 0 until checkLen) {
            if (s1[i] == s2[i]) matchingChars++
        }
        return (matchingChars / maxLen) > 0.85
    }

    override fun onInterrupt() {
        Log.w(TAG, "KindleTextExtractorService interrupted.")
    }
}
