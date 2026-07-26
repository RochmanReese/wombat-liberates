package com.techwombat.liberates

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    private var batchActive = false
    private var batchPageCount = 0
    private var batchPagesCompleted = 0
    private var batchStatus = "No batch running."
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ocrExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val batchRunnable: Runnable = Runnable {
        if (!batchActive || !ProbeLog.isActive) return@Runnable
        if (!isKindleForeground()) {
            mainHandler.postDelayed(batchRunnable, BATCH_FOREGROUND_CHECK_MS)
            return@Runnable
        }
        captureBatchPage()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        PersistentProbeLog.initialize(applicationContext)
        OcrTextStore.initialize(applicationContext)
        activeService = this
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        mainHandler.removeCallbacks(batchRunnable)
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

    private fun isKindleForeground(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            root.packageName?.toString() == KINDLE_PACKAGE
        } finally {
            root.recycle()
        }
    }

    private fun captureOnePageForOcr(onComplete: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ProbeLog.appendDiagnostic("ONE-PAGE OCR ERROR", "Screenshot capture requires Android 11 or later.")
            onComplete?.invoke(false)
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
                    onComplete?.invoke(false)
                    return
                }
                val readingBitmap = cropToReadingArea(bitmap)
                bitmap.recycle()
                recognizeScreenshot(readingBitmap, onComplete)
            }

            override fun onFailure(errorCode: Int) {
                ProbeLog.appendDiagnostic("ONE-PAGE OCR ERROR", screenshotError(errorCode))
                onComplete?.invoke(false)
            }
        })
    }

    private fun cropToReadingArea(bitmap: Bitmap): Bitmap {
        val top = (bitmap.height * 0.06f).toInt()
        val bottom = (bitmap.height * 0.03f).toInt()
        return Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bitmap.height - top - bottom)
    }

    private fun recognizeScreenshot(bitmap: Bitmap, onComplete: ((Boolean) -> Unit)? = null) {
        val image = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener(ocrExecutor) { result ->
                bitmap.recycle()
                val text = cleanRecognizedText(result)
                if (text.isBlank()) {
                    ProbeLog.appendDiagnostic("ONE-PAGE OCR RESULT", "No text recognized.")
                    onComplete?.invoke(false)
                } else {
                    OcrTextStore.appendPage(text)
                    ProbeLog.appendDiagnostic("ONE-PAGE OCR RESULT", "Clean text saved for export:\n$text")
                    onComplete?.invoke(true)
                }
            }
            .addOnFailureListener(ocrExecutor) { error ->
                bitmap.recycle()
                ProbeLog.appendDiagnostic(
                    "ONE-PAGE OCR ERROR",
                    "Recognition failed: ${error.message ?: error.javaClass.simpleName}",
                )
                onComplete?.invoke(false)
            }
    }

    private fun captureBatchPage() {
        val pageNumber = batchPagesCompleted + 1
        ProbeLog.appendDiagnostic("BATCH OCR", "Capturing page $pageNumber of $batchPageCount.")
        captureOnePageForOcr { succeeded ->
            mainHandler.post {
                if (!batchActive) return@post
                if (!succeeded) {
                    finishBatch("Batch stopped: OCR failed on page $pageNumber of $batchPageCount.")
                    return@post
                }
                batchPagesCompleted += 1
                if (batchPagesCompleted >= batchPageCount) {
                    finishBatch("Batch complete: captured $batchPagesCompleted pages.")
                } else {
                    turnToNextPage()
                }
            }
        }
    }

    private fun turnToNextPage() {
        if (!isKindleForeground()) {
            finishBatch("Batch stopped: Kindle is no longer in the foreground.")
            return
        }
        val metrics = resources.displayMetrics
        val tapPath = Path().apply {
            moveTo(metrics.widthPixels * NEXT_PAGE_X_FRACTION, metrics.heightPixels * NEXT_PAGE_Y_FRACTION)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(tapPath, 0, NEXT_PAGE_TAP_DURATION_MS))
            .build()
        if (!dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (batchActive) mainHandler.postDelayed(batchRunnable, BATCH_PAGE_SETTLE_MS)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    finishBatch("Batch stopped: Kindle page-turn gesture was cancelled.")
                }
            }, mainHandler)) {
            finishBatch("Batch stopped: Android rejected the Kindle page-turn gesture.")
        }
    }

    private fun finishBatch(message: String) {
        batchActive = false
        mainHandler.removeCallbacks(batchRunnable)
        batchStatus = message
        ProbeLog.appendDiagnostic("BATCH OCR", message)
    }

    private fun cleanRecognizedText(result: com.google.mlkit.vision.text.Text): String = result.textBlocks
        .map { block -> block.lines.map { line -> line.text.trim() }.filter { it.isNotBlank() } }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n") { lines -> joinVisualLines(lines) }

    private fun joinVisualLines(lines: List<String>): String = buildString {
        lines.forEachIndexed { index, line ->
            if (index == 0) {
                append(line)
            } else if (endsWith("-")) {
                deleteCharAt(length - 1)
                append(line)
            } else {
                append(' ')
                append(line)
            }
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
        private const val MAX_BATCH_PAGES = 10
        private const val BATCH_FOREGROUND_CHECK_MS = 500L
        private const val BATCH_PAGE_SETTLE_MS = 1_200L
        private const val NEXT_PAGE_X_FRACTION = 0.86f
        private const val NEXT_PAGE_Y_FRACTION = 0.50f
        private const val NEXT_PAGE_TAP_DURATION_MS = 50L

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

        fun startBatch(pageCount: Int): Boolean {
            val service = activeService ?: return false
            if (!ProbeLog.isActive || pageCount !in 1..MAX_BATCH_PAGES) return false
            service.mainHandler.removeCallbacks(service.batchRunnable)
            service.batchPageCount = pageCount
            service.batchPagesCompleted = 0
            service.batchActive = true
            service.batchStatus = "Batch armed: waiting for Kindle to be foreground."
            ProbeLog.appendDiagnostic("BATCH OCR", "Batch armed for $pageCount pages; waiting for Kindle.")
            service.mainHandler.post(service.batchRunnable)
            return true
        }

        fun stopBatch() {
            activeService?.let { service ->
                if (service.batchActive) service.finishBatch("Batch stopped by user after ${service.batchPagesCompleted} pages.")
            }
        }

        fun batchStatus(): String = activeService?.batchStatus ?: "Accessibility service is not connected."
    }
}
