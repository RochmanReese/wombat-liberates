package com.techwombat.liberates

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class KindleTextExtractorService : AccessibilityService() {

    companion object {
        private const val TAG = "WOMBAT_LIBERATES"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: ""
        if (!packageName.contains("kindle") && !packageName.contains("reading")) {
            return
        }

        val rootNode = rootInActiveWindow ?: return
        Log.d(TAG, "=== Event from $packageName: ${AccessibilityEvent.eventTypeToString(event.eventType)} ===")

        val extractedTextLines = mutableListOf<String>()
        traverseNodeTree(rootNode, extractedTextLines)

        if (extractedTextLines.isNotEmpty()) {
            Log.d(TAG, "[KINDLE_PAGE_START] Captured ${extractedTextLines.size} text nodes:")
            extractedTextLines.forEachIndexed { index, line ->
                Log.d(TAG, "[KINDLE_NODE] [$index] $line")
            }
            Log.d(TAG, "[KINDLE_PAGE_END]")
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

    override fun onInterrupt() {
        Log.w(TAG, "KindleTextExtractorService interrupted.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Wombat-Liberates Kindle Accessibility Service Connected!")
    }
}
