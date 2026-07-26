package com.techwombat.liberates

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
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
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoSwipeRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Wombat-Liberates Kindle Accessibility Service Connected!")
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
                        // Humanized random delay between 1500ms and 2100ms
                        nextDelay = 1500L + Random.nextLong(600)
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
        if (packageName.isNotEmpty() && packageName != "com.techwombat.liberates") {
            getPrefs(this).edit().putString(PREF_LAST_PACKAGE, packageName).apply()
        }

        val capturing = isCapturing(this)
        if (!capturing) return

        val rootNode = rootInActiveWindow ?: return

        val extractedLines = mutableListOf<String>()
        traverseNodeTree(rootNode, extractedLines)

        if (extractedLines.isEmpty()) return

        val combinedContent = extractedLines.joinToString("\n")
        val contentHash = computeHash(combinedContent)

        val existingPages = getCapturedPages(this).toMutableList()
        val lastPage = existingPages.lastOrNull()

        // Deduplication Check 1: Exact Hash Match
        if (lastPage != null && lastPage.contentHash == contentHash) {
            return
        }

        // Deduplication Check 2: High Content Similarity (>85% character overlap)
        if (lastPage != null && isSimilarContent(lastPage.textLines.joinToString("\n"), combinedContent)) {
            Log.d(TAG, "Skipped duplicate/similar page redraw.")
            return
        }

        val newPageNum = existingPages.size + 1
        val newPage = CapturedPage(
            pageNumber = newPageNum,
            textLines = extractedLines,
            contentHash = contentHash,
            packageName = packageName
        )

        existingPages.add(newPage)
        savePages(existingPages)

        getPrefs(this).edit()
            .putInt(PREF_PAGE_COUNT, newPageNum)
            .putInt(PREF_LAST_LINE_COUNT, extractedLines.size)
            .apply()

        Log.i(TAG, "[PAGE_CAPTURED] Added Page #$newPageNum (${extractedLines.size} lines from $packageName)")
    }

    fun dispatchSwipeGesture() {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        // Humanized dynamic coordinates with random Y-variation (45% to 55%)
        val startX = width * (0.83f + Random.nextFloat() * 0.04f) // ~83% to 87%
        val endX = width * (0.13f + Random.nextFloat() * 0.04f)   // ~13% to 17%
        val startY = height * (0.45f + Random.nextFloat() * 0.10f) // ~45% to 55%
        val endY = startY + (Random.nextFloat() * 20f - 10f)       // Natural slight vertical curve

        val swipePath = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        // Humanized duration (200ms to 280ms)
        val strokeDuration = 200L + Random.nextLong(80)

        val gestureBuilder = GestureDescription.Builder()
        val stroke = GestureDescription.StrokeDescription(swipePath, 0, strokeDuration)
        gestureBuilder.addStroke(stroke)

        Log.d(TAG, "Executing humanized auto-swipe gesture (X: ${startX.toInt()} -> ${endX.toInt()}, Y: ${startY.toInt()}, ${strokeDuration}ms)")

        dispatchGesture(
            gestureBuilder.build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Humanized swipe completed.")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "Swipe gesture cancelled by OS.")
                }
            },
            mainHandler
        )
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
        if (!text.isNullOrEmpty()) {
            outputList.add(text)
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
