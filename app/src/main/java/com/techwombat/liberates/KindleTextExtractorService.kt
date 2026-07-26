package com.techwombat.liberates

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest

data class CapturedPage(
    val pageNumber: Int,
    val textLines: List<String>,
    val contentHash: String,
    val timestamp: Long = System.currentTimeMillis()
)

class KindleTextExtractorService : AccessibilityService() {

    companion object {
        private const val TAG = "WOMBAT_LIBERATES"
        
        const val ACTION_START_CAPTURE = "com.techwombat.liberates.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.techwombat.liberates.STOP_CAPTURE"
        const val ACTION_CLEAR_BUFFER = "com.techwombat.liberates.CLEAR_BUFFER"
        const val ACTION_PAGE_CAPTURED = "com.techwombat.liberates.PAGE_CAPTURED"
        
        const val EXTRA_PAGE_COUNT = "extra_page_count"
        const val EXTRA_LAST_LINE_COUNT = "extra_last_line_count"
        const val EXTRA_LAST_PACKAGE = "extra_last_package"
        
        @Volatile var isCapturing: Boolean = false
            private set

        val capturedPages = mutableListOf<CapturedPage>()
        var lastDetectedPackage: String = "None"
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_START_CAPTURE -> {
                    isCapturing = true
                    Log.i(TAG, "=== Capture Mode ENABLED via Broadcast ===")
                }
                ACTION_STOP_CAPTURE -> {
                    isCapturing = false
                    Log.i(TAG, "=== Capture Mode DISABLED. Total pages captured: ${capturedPages.size} ===")
                }
                ACTION_CLEAR_BUFFER -> {
                    synchronized(capturedPages) {
                        capturedPages.clear()
                    }
                    Log.i(TAG, "=== Page Buffer Cleared ===")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(ACTION_START_CAPTURE)
            addAction(ACTION_STOP_CAPTURE)
            addAction(ACTION_CLEAR_BUFFER)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(controlReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: ""
        if (packageName.isNotEmpty() && packageName != "com.techwombat.liberates") {
            lastDetectedPackage = packageName
        }

        if (!isCapturing) return

        val rootNode = rootInActiveWindow ?: return

        val extractedLines = mutableListOf<String>()
        traverseNodeTree(rootNode, extractedLines)

        if (extractedLines.isEmpty()) return

        val combinedContent = extractedLines.joinToString("\n")
        val contentHash = computeHash(combinedContent)

        synchronized(capturedPages) {
            val lastPage = capturedPages.lastOrNull()
            
            // Deduplication Check 1: Exact Hash Match
            if (lastPage != null && lastPage.contentHash == contentHash) {
                return
            }

            // Deduplication Check 2: High Content Similarity (>85% character overlap)
            if (lastPage != null && isSimilarContent(lastPage.textLines.joinToString("\n"), combinedContent)) {
                Log.d(TAG, "Skipped duplicate/similar page redraw.")
                return
            }

            val newPageNum = capturedPages.size + 1
            val newPage = CapturedPage(
                pageNumber = newPageNum,
                textLines = extractedLines,
                contentHash = contentHash
            )

            capturedPages.add(newPage)
            Log.i(TAG, "[PAGE_CAPTURED] Added Page #$newPageNum (${extractedLines.size} lines from $packageName)")

            // Broadcast page update to MainActivity
            val updateIntent = Intent(ACTION_PAGE_CAPTURED).apply {
                putExtra(EXTRA_PAGE_COUNT, newPageNum)
                putExtra(EXTRA_LAST_LINE_COUNT, extractedLines.size)
                putExtra(EXTRA_LAST_PACKAGE, packageName)
                setPackage("com.techwombat.liberates")
            }
            sendBroadcast(updateIntent)
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Wombat-Liberates Kindle Accessibility Service Connected!")
    }
}
