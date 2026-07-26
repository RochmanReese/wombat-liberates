package com.techwombat.liberates

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class KindleAccessibilityProbeService : AccessibilityService() {
    @Volatile
    private var treeSnapshotArmed = false

    @Volatile
    private var onePageOcrArmed = false

    private val ocrExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onServiceConnected() {
        super.onServiceConnected()
        PersistentProbeLog.initialize(applicationContext)
        activeService = this
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        textRecognizer.close()
        ocrExecutor.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!ProbeLog.isActive || event.packageName?.toString() != KINDLE_PACKAGE) return

        if (treeSnapshotArmed) {
            treeSnapshotArmed = false
            captureActiveWindowTree()
        }
        if (onePageOcrArmed) {
            onePageOcrArmed = false
            captureOnePageForOcr()
        }

        val eventText = event.text.joinToString(" | ") { it?.toString().orEmpty() }.ifBlank { "(none)" }
        val sourceText = event.source?.readableText().orEmpty().ifBlank { "(none)" }
        ProbeLog.append(AccessibilityEvent.eventTypeToString(event.eventType), eventText, sourceText)
    }

    override fun onInterrupt() = Unit

    private fun captureOnePageForOcr() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ProbeLog.appendDiagnostic("ONE-PAGE OCR ERROR", "Screenshot capture requires Android 11 or later.")
            return
        }
        ProbeLog.appendDiagnostic("ONE-PAGE OCR", "Taking one accessibility screenshot; it will not be saved.")
        takeScreenshot(Display.DEFAULT_DISPLAY, ocrExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val bitmap = screenshot.hardwareBuffer.use { buffer ->
                    Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                }
                if (bitmap == null) {
                    ProbeLog.appendDiagnostic("ONE-PAGE OCR ERROR", "Screenshot returned no readable bitmap.")
                    return
                }
                recognizeScreenshot(bitmap)
            }

            override fun onFailure(errorCode: Int) {
                ProbeLog.appendDiagnostic("ONE-PAGE OCR ERROR", screenshotError(errorCode))
            }
        })
    }

    private fun recognizeScreenshot(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener(ocrExecutor) { result ->
                bitmap.recycle()
                val text = result.text.trim()
                if (text.isBlank()) {
                    ProbeLog.appendDiagnostic("ONE-PAGE OCR RESULT", "No text recognized.")
                } else {
                    ProbeLog.appendDiagnostic("ONE-PAGE OCR RESULT", text)
                }
            }
            .addOnFailureListener(ocrExecutor) { error ->
                bitmap.recycle()
                ProbeLog.appendDiagnostic(
                    "ONE-PAGE OCR ERROR",
                    "Recognition failed: ${error.message ?: error.javaClass.simpleName}",
                )
            }
    }

    private fun screenshotError(errorCode: Int): String = when (errorCode) {
        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "Secure window; screenshot blocked."
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "Screenshot access was denied by Android."
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "The default display is unavailable."
        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "Android could not take the screenshot."
        else -> "Android screenshot error code: $errorCode"
    }

    private fun captureActiveWindowTree() {
        val lines = mutableListOf<String>()
        lines += "[${timestamp()}] ONE-TIME ACTIVE WINDOW TREE SNAPSHOT"
        val root = rootInActiveWindow
        if (root == null) {
            lines += "root: (none)"
        } else {
            appendNode(root, 0, lines, intArrayOf(0))
            root.recycle()
        }
        PersistentProbeLog.append(lines.joinToString("\n"))
    }

    private fun appendNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        lines: MutableList<String>,
        count: IntArray,
    ) {
        if (count[0]++ >= MAX_TREE_NODES) {
            if (count[0] == MAX_TREE_NODES + 1) lines += "... node limit reached ..."
            return
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        val indent = "  ".repeat(depth)
        lines += "$indent- class=${node.className.display()} id=${node.viewIdResourceName.display()} text=${node.text.display()} desc=${node.contentDescription.display()} bounds=$bounds children=${node.childCount}"
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            appendNode(child, depth + 1, lines, count)
            child.recycle()
        }
    }

    private fun AccessibilityNodeInfo.readableText(): String =
        listOfNotNull(text?.toString(), contentDescription?.toString()).joinToString(" | ")

    private fun CharSequence?.display(): String =
        this?.toString()?.replace("\n", "\\n")?.ifBlank { "(none)" } ?: "(none)"

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    companion object {
        private const val KINDLE_PACKAGE = "com.amazon.kindle"
        private const val MAX_TREE_NODES = 400

        @Volatile
        private var activeService: KindleAccessibilityProbeService? = null

        fun armNextTreeSnapshot(): Boolean {
            val service = activeService ?: return false
            if (!ProbeLog.isActive) return false
            service.treeSnapshotArmed = true
            return true
        }

        fun armOnePageOcrCapture(): Boolean {
            val service = activeService ?: return false
            if (!ProbeLog.isActive) return false
            service.onePageOcrArmed = true
            return true
        }
    }
}
