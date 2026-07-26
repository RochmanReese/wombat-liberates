package com.techwombat.liberates

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
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
import java.util.concurrent.Executor
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
    private val mainExecutor = Executor { command -> mainHandler.post(command) }
    private var autoSwipeRunnable: Runnable? = null
    private var currentPackageName = ""
    private var isOcrPending = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Wombat-Liberates Kindle Accessibility Service Connected!")
        logToFile(this, "SERVICE CONNECTED")
        startAutoSwipeLoop()
    }

    private fun startAutoSwipeLoop() {
        autoSwipeRunnable = object : Runnable {
            override fun run() {
                var nextDelay = 2200L
                try {
                    val capturing = isCapturing(this@KindleTextExtractorService)
                    val autoSwipe = isAutoSwipe(this@KindleTextExtractorService)

                    if (capturing && autoSwipe) {
                        dispatchSwipeGesture()
                        nextDelay = 2000L + Random.nextLong(600)

                        mainHandler.postDelayed({
                            captureScreenOcr()
                        }, 500)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in auto-swipe loop", e)
                } finally {
                    mainHandler.postDelayed(this, nextDelay)
                }
            }
        }
        mainHandler.postDelayed(autoSwipeRunnable!!, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        autoSwipeRunnable?.let { mainHandler.removeCallbacks(it) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: ""

        if (pkg.isNotEmpty() &&
            pkg != "com.techwombat.liberates" &&
            pkg != "com.android.systemui" &&
            !pkg.contains("launcher", ignoreCase = true) &&
            !pkg.contains("inputmethod", ignoreCase = true) &&
            !pkg.contains("keyboard", ignoreCase = true) &&
            pkg != "android"
        ) {
            currentPackageName = pkg
            getPrefs(this).edit().putString(PREF_LAST_PACKAGE, currentPackageName).apply()
        }
    }

    private fun captureScreenOcr() {
        if (isOcrPending) return
        val capturing = isCapturing(this)
        if (!capturing) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            isOcrPending = true
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            try {
                                val hwBuffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                hwBuffer.close()

                                if (bitmap != null) {
                                    val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    bitmap.recycle()

                                    OcrExtractor.extractTextFromBitmap(softwareBitmap) { cleanLines ->
                                        isOcrPending = false
                                        processExtractedPage(cleanLines)
                                    }
                                } else {
                                    logToFile(this@KindleTextExtractorService, "takeScreenshot onSuccess: bitmap was null")
                                    isOcrPending = false
                                }
                            } catch (e: Exception) {
                                logToFile(this@KindleTextExtractorService, "takeScreenshot onSuccess Exception: ${e.message}")
                                isOcrPending = false
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            logToFile(this@KindleTextExtractorService, "takeScreenshot onFailure code: $errorCode")
                            isOcrPending = false
                        }
                    }
                )
            } catch (e: Exception) {
                logToFile(this@KindleTextExtractorService, "takeScreenshot Exception: ${e.message}")
                isOcrPending = false
            }
        }
    }

    private fun processExtractedPage(cleanLines: List<String>) {
        if (cleanLines.isEmpty()) {
            logToFile(this, "OCR processExtractedPage: cleanLines was empty")
            return
        }

        val combinedContent = cleanLines.joinToString("\n")
        val contentHash = computeHash(combinedContent)

        val existingPages = getCapturedPages(this).toMutableList()
        val lastPage = existingPages.lastOrNull()

        if (lastPage != null && lastPage.contentHash == contentHash) {
            logToFile(this, "OCR processExtractedPage: skipped exact hash duplicate")
            return
        }

        if (lastPage != null && isSimilarContent(lastPage.textLines.joinToString("\n"), combinedContent)) {
            logToFile(this, "OCR processExtractedPage: skipped similar content duplicate")
            return
        }

        val pkgToUse = if (currentPackageName.isNotEmpty()) currentPackageName else "com.amazon.kindle"
        val newPageNum = existingPages.size + 1
        val newPage = CapturedPage(
            pageNumber = newPageNum,
            textLines = cleanLines,
            contentHash = contentHash,
            packageName = pkgToUse
        )

        existingPages.add(newPage)
        savePages(existingPages)

        getPrefs(this).edit()
            .putInt(PREF_PAGE_COUNT, newPageNum)
            .putInt(PREF_LAST_LINE_COUNT, cleanLines.size)
            .apply()

        logToFile(this, "OCR CAPTURED PAGE #$newPageNum (${cleanLines.size} lines from $pkgToUse)")
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
