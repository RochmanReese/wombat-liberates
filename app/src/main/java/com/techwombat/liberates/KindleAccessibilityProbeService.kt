package com.techwombat.liberates

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
    /** Maximum screens for this scan, or null to scan until the end of the book. */
    private var batchPageLimit: Int? = null
    private var batchPagesCompleted = 0
    private var batchContinuing = false
    private var batchAwaitingFirstCapture = false
    private var consecutiveBlankPages = 0
    private var blankPagesSkipped = 0
    private var batchIsTest = false
    private var tablesSkipped = 0
    private var tablePolicy = ScanSettings.TableMode.ASK
    private var lastProgressPercent: Int? = null
    private var gestureRetries = 0
    private var promptView: View? = null
    private var batchTarget: OcrTextFiles? = null
    private var batchStatus = "No batch running."
    private var lastBatchFingerprint: IntArray? = null
    private var unchangedPageAttempts = 0
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
        hidePrompt()
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

    private fun captureOnePageForOcr(onComplete: ((OcrCapture?) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ProbeLog.appendDiagnostic("ONE-PAGE OCR ERROR", "Screenshot capture requires Android 11 or later.")
            onComplete?.invoke(null)
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
                    onComplete?.invoke(null)
                    return
                }
                val readingBitmap = cropToReadingArea(bitmap)
                bitmap.recycle()
                recognizeScreenshot(readingBitmap, readingFingerprint(readingBitmap), onComplete)
            }

            override fun onFailure(errorCode: Int) {
                ProbeLog.appendDiagnostic("ONE-PAGE OCR ERROR", screenshotError(errorCode))
                onComplete?.invoke(null)
            }
        })
    }

    private fun cropToReadingArea(bitmap: Bitmap): Bitmap {
        val top = (bitmap.height * 0.06f).toInt()
        val bottom = (bitmap.height * 0.03f).toInt()
        return Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bitmap.height - top - bottom)
    }

    private fun readingFingerprint(bitmap: Bitmap): IntArray {
        val fingerprint = IntArray(FINGERPRINT_WIDTH * FINGERPRINT_HEIGHT)
        var index = 0
        for (y in 0 until FINGERPRINT_HEIGHT) {
            val sourceY = y * (bitmap.height - 1) / (FINGERPRINT_HEIGHT - 1)
            for (x in 0 until FINGERPRINT_WIDTH) {
                val sourceX = x * (bitmap.width - 1) / (FINGERPRINT_WIDTH - 1)
                val pixel = bitmap.getPixel(sourceX, sourceY)
                fingerprint[index++] = ((pixel shr 16 and 0xff) * 30 + (pixel shr 8 and 0xff) * 59 + (pixel and 0xff) * 11) / 100
            }
        }
        return fingerprint
    }

    private fun isSameReadingScreen(first: IntArray, second: IntArray): Boolean {
        if (first.size != second.size) return false
        var totalDifference = 0L
        first.indices.forEach { index -> totalDifference += kotlin.math.abs(first[index] - second[index]) }
        return totalDifference.toDouble() / first.size < MAX_FINGERPRINT_AVERAGE_DIFFERENCE
    }

    private fun recognizeScreenshot(
        bitmap: Bitmap,
        fingerprint: IntArray,
        onComplete: ((OcrCapture?) -> Unit)? = null,
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val imageHeight = bitmap.height
        textRecognizer.process(image)
            .addOnSuccessListener(ocrExecutor) { result ->
                bitmap.recycle()
                val page = PageLayout.analyze(ocrBlocks(result), imageHeight)
                val text = page.text
                if (onComplete != null) {
                    // Batch scans decide what to do with blank pages, tables and Kindle's end screen.
                    onComplete(OcrCapture(page, fingerprint))
                } else if (text.isBlank()) {
                    ProbeLog.appendDiagnostic("ONE-PAGE OCR RESULT", "No text recognized.")
                } else {
                    OcrTextStore.appendPage(text)
                    ProbeLog.appendDiagnostic("ONE-PAGE OCR RESULT", "Clean text saved for export:\n$text")
                }
            }
            .addOnFailureListener(ocrExecutor) { error ->
                bitmap.recycle()
                ProbeLog.appendDiagnostic(
                    "ONE-PAGE OCR ERROR",
                    "Recognition failed: ${error.message ?: error.javaClass.simpleName}",
                )
                onComplete?.invoke(null)
            }
    }

    private fun screenLabel(screenNumber: Int): String =
        batchPageLimit?.let { "screen $screenNumber of $it" } ?: "screen $screenNumber"

    private fun captureBatchPage() {
        hidePrompt()
        val screenNumber = batchPagesCompleted + 1
        ProbeLog.appendDiagnostic("BATCH OCR", "Capturing ${screenLabel(screenNumber)}.")
        captureOnePageForOcr { capture -> mainHandler.post { handleBatchCapture(capture, screenNumber) } }
    }

    private fun handleBatchCapture(capture: OcrCapture?, screenNumber: Int) {
        if (!batchActive) return
        if (capture == null) {
            finishBatch("Batch stopped: OCR failed on ${screenLabel(screenNumber)}.")
            return
        }
        val page = capture.page
        page.progressPercent?.let { lastProgressPercent = it }

        if (page.isEndOfBookScreen) {
            // Kindle's "About this book / Review" screen: not book text, and its buttons must not be tapped.
            finishBatch("Batch complete: reached Kindle's end-of-book screen after $batchPagesCompleted screens.")
            return
        }

        val firstCapture = batchAwaitingFirstCapture
        batchAwaitingFirstCapture = false
        if (firstCapture && batchContinuing && isPreviouslySavedPage(capture)) {
            lastBatchFingerprint = capture.fingerprint
            ProbeLog.appendDiagnostic("BATCH OCR", "Kindle is still on the last saved page; skipping it so it is not duplicated.")
            turnToNextPage()
            return
        }

        val previousFingerprint = lastBatchFingerprint
        if (!firstCapture && previousFingerprint != null && isSameReadingScreen(previousFingerprint, capture.fingerprint)) {
            unchangedPageAttempts += 1
            handleStuckPage()
            return
        }

        unchangedPageAttempts = 0
        lastBatchFingerprint = capture.fingerprint
        batchPagesCompleted += 1
        when {
            page.text.isBlank() -> {
                blankPagesSkipped += 1
                consecutiveBlankPages += 1
                if (consecutiveBlankPages >= MAX_CONSECUTIVE_BLANK_PAGES) {
                    finishBatch("Batch stopped: no text found on $consecutiveBlankPages screens in a row (last was ${screenLabel(screenNumber)}).")
                    return
                }
                ProbeLog.appendDiagnostic("BATCH OCR", "No text on ${screenLabel(screenNumber)} (picture or blank page); skipped.")
                advance()
            }
            page.isTable -> {
                consecutiveBlankPages = 0
                handleTablePage(page, screenNumber)
            }
            else -> {
                consecutiveBlankPages = 0
                savePage(page.text, screenNumber)
                advance()
            }
        }
    }

    private fun savePage(text: String, screenNumber: Int, kind: String = "") {
        (batchTarget ?: OcrTextStore.main).appendPage(text)
        ProbeLog.appendDiagnostic("BATCH OCR", "Saved ${kind}${screenLabel(screenNumber)}: ${text.length} characters.")
    }

    /** Turns to the next page unless the screen limit has been reached. */
    private fun advance() {
        val limit = batchPageLimit
        if (limit != null && batchPagesCompleted >= limit) {
            finishBatch("Batch complete: captured $batchPagesCompleted screens (screen limit reached).")
        } else {
            turnToNextPage()
        }
    }

    /**
     * Kindle did not change the screen. Try other ways of turning the page first; a tap can land on a
     * table, picture or link. Then finish if Kindle's footer says the book is done, otherwise ask the user.
     */
    private fun handleStuckPage() {
        when (unchangedPageAttempts) {
            1 -> {
                ProbeLog.appendDiagnostic("BATCH OCR", "Kindle screen did not advance; tapping again.")
                turnToNextPage(PageTurn.TAP)
            }
            2 -> {
                ProbeLog.appendDiagnostic("BATCH OCR", "Kindle screen still did not advance; trying a swipe.")
                turnToNextPage(PageTurn.SWIPE)
            }
            3 -> {
                ProbeLog.appendDiagnostic("BATCH OCR", "Kindle screen still did not advance; tapping lower on the page.")
                turnToNextPage(PageTurn.TAP_LOW)
            }
            else -> {
                val progress = lastProgressPercent
                if (progress != null && progress >= END_OF_BOOK_PERCENT) {
                    finishBatch("Batch complete: end of book (Kindle showed $progress%) after $batchPagesCompleted screens.")
                } else {
                    askAboutStuckPage()
                }
            }
        }
    }

    private fun askAboutStuckPage() {
        val progress = lastProgressPercent?.let { " Kindle last showed $it%." } ?: ""
        ProbeLog.appendDiagnostic("BATCH OCR", "Kindle did not turn the page after several tries; asking the user.")
        showPrompt(
            title = "Scan paused",
            message = "Kindle didn't turn the page after several tries (maybe a table, picture or link).$progress " +
                "Turn the page yourself in Kindle, then tap Continue. Everything scanned so far is saved.",
            rows = listOf(
                listOf("Continue" to {
                    hidePrompt()
                    unchangedPageAttempts = 0
                    mainHandler.postDelayed(batchRunnable, PROMPT_RESUME_DELAY_MS)
                }),
                listOf("This is the end of the book" to {
                    finishBatch("Batch complete: end of book confirmed by you after $batchPagesCompleted screens.")
                }),
                listOf("Stop scan" to { finishBatch("Batch stopped by user after $batchPagesCompleted screens.") }),
            ),
        )
    }

    private fun handleTablePage(page: PageAnalysis, screenNumber: Int) {
        fun keep() {
            savePage(page.text, screenNumber, kind = "table on ")
            resumeAfterPrompt()
        }
        fun skip() {
            tablesSkipped += 1
            ProbeLog.appendDiagnostic("BATCH OCR", "Skipped table on ${screenLabel(screenNumber)}.")
            resumeAfterPrompt()
        }
        when (tablePolicy) {
            ScanSettings.TableMode.KEEP -> keep()
            ScanSettings.TableMode.SKIP -> skip()
            ScanSettings.TableMode.ASK -> {
                ProbeLog.appendDiagnostic("BATCH OCR", "Table found on ${screenLabel(screenNumber)}; asking the user.")
                val preview = page.text.lines().filter { it.isNotBlank() }.take(TABLE_PREVIEW_LINES).joinToString("\n")
                showPrompt(
                    title = "Table on ${screenLabel(screenNumber)}",
                    message = "This page looks like a table. Keep its text (read row by row) or skip it?\n\n$preview",
                    rows = listOf(
                        listOf("Keep" to { keep() }, "Skip" to { skip() }),
                        listOf(
                            "Keep all tables" to {
                                tablePolicy = ScanSettings.TableMode.KEEP
                                keep()
                            },
                            "Skip all tables" to {
                                tablePolicy = ScanSettings.TableMode.SKIP
                                skip()
                            },
                        ),
                        listOf("Stop scan" to { finishBatch("Batch stopped by user after $batchPagesCompleted screens.") }),
                    ),
                )
            }
        }
    }

    /** Hides any prompt and continues, giving Android a moment to remove the panel before the next tap. */
    private fun resumeAfterPrompt() {
        if (promptView == null) {
            advance()
        } else {
            hidePrompt()
            mainHandler.postDelayed({ if (batchActive) advance() }, PROMPT_RESUME_DELAY_MS)
        }
    }

    /** True when a continued scan starts on the page that the previous scan already saved. */
    private fun isPreviouslySavedPage(capture: OcrCapture): Boolean {
        val previousFingerprint = lastBatchFingerprint
        if (previousFingerprint != null && isSameReadingScreen(previousFingerprint, capture.fingerprint)) return true
        val lastSaved = (batchTarget ?: OcrTextStore.main).lastPageText().trim()
        return lastSaved.isNotBlank() && lastSaved == capture.page.text.trim()
    }

    private enum class PageTurn { TAP, SWIPE, TAP_LOW }

    private fun turnToNextPage(kind: PageTurn = PageTurn.TAP) {
        if (!isKindleForeground()) {
            finishBatch("Batch stopped: Kindle is no longer in the foreground.")
            return
        }
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val path = Path()
        val duration = when (kind) {
            PageTurn.TAP -> {
                path.moveTo(width * NEXT_PAGE_X_FRACTION, height * NEXT_PAGE_Y_FRACTION)
                NEXT_PAGE_TAP_DURATION_MS
            }
            PageTurn.TAP_LOW -> {
                path.moveTo(width * LOW_TAP_X_FRACTION, height * LOW_TAP_Y_FRACTION)
                NEXT_PAGE_TAP_DURATION_MS
            }
            PageTurn.SWIPE -> {
                path.moveTo(width * SWIPE_START_X_FRACTION, height * NEXT_PAGE_Y_FRACTION)
                path.lineTo(width * SWIPE_END_X_FRACTION, height * NEXT_PAGE_Y_FRACTION)
                SWIPE_DURATION_MS
            }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        if (!dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gestureRetries = 0
                    if (batchActive) mainHandler.postDelayed(batchRunnable, BATCH_PAGE_SETTLE_MS)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    // Usually the screen was touched mid-gesture; wait and try again before giving up.
                    if (batchActive && gestureRetries++ < MAX_GESTURE_RETRIES) {
                        ProbeLog.appendDiagnostic("BATCH OCR", "Page-turn gesture was interrupted; retrying.")
                        mainHandler.postDelayed({ if (batchActive) turnToNextPage(kind) }, GESTURE_RETRY_DELAY_MS)
                    } else {
                        finishBatch("Batch stopped: Kindle page-turn gesture was cancelled.")
                    }
                }
            }, mainHandler)) {
            finishBatch("Batch stopped: Android rejected the Kindle page-turn gesture.")
        }
    }

    private fun finishBatch(message: String) {
        val wasActive = batchActive
        batchActive = false
        mainHandler.removeCallbacks(batchRunnable)
        hidePrompt()
        val tables = if (tablesSkipped > 0) " $tablesSkipped table pages skipped." else ""
        batchStatus = message + tables
        ProbeLog.appendDiagnostic("BATCH OCR", batchStatus)
        if (wasActive) {
            val workspace = if (batchIsTest) Workspaces.test(this) else Workspaces.book(this)
            workspace.scanSummary = ScanSummary(
                screensRequested = batchPageLimit ?: 0,
                screensCaptured = batchPagesCompleted,
                blankPagesSkipped = blankPagesSkipped,
                stopReason = batchStatus,
                finishedAtMillis = System.currentTimeMillis(),
            )
            // Accessibility services may start activities from the background; bring the user to the review screen.
            startActivity(
                ReviewActivity.intent(this, workspace.key)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }

    // ---- Prompt panel shown over Kindle ----

    /** Shows a small panel over Kindle (an accessibility overlay needs no extra permission). */
    private fun showPrompt(title: String, message: String, rows: List<List<Pair<String, () -> Unit>>>) {
        hidePrompt()
        val context = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                setColor(Color.argb(0xF2, 0x1E, 0x1E, 0x1E))
                cornerRadius = 16 * density
            }
        }
        panel.addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        panel.addView(TextView(context).apply {
            text = message
            textSize = 14f
            setTextColor(Color.rgb(0xDD, 0xDD, 0xDD))
            setPadding(0, padding / 2, 0, padding / 2)
        })
        rows.forEach { row ->
            val rowLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { (label, action) ->
                rowLayout.addView(
                    Button(context).apply {
                        text = label
                        isAllCaps = false
                        setOnClickListener { action() }
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
            }
            panel.addView(rowLayout)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            y = (24 * density).toInt()
        }
        runCatching { getSystemService(WindowManager::class.java).addView(panel, params) }
            .onSuccess {
                promptView = panel
                getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
            }
            .onFailure { finishBatch("Batch stopped: could not show the question panel (${it.message}).") }
    }

    private fun hidePrompt() {
        val view = promptView ?: return
        promptView = null
        runCatching { getSystemService(WindowManager::class.java).removeView(view) }
    }

    /** Converts ML Kit's result into plain OCR lines with positions, grouped by block. */
    private fun ocrBlocks(result: com.google.mlkit.vision.text.Text): List<List<OcrLine>> =
        result.textBlocks.map { block ->
            block.lines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                OcrLine(line.text.trim(), box.left, box.top, box.right, box.bottom)
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

    private data class OcrCapture(val page: PageAnalysis, val fingerprint: IntArray)

    companion object {
        private const val KINDLE_PACKAGE = "com.amazon.kindle"
        private const val MAX_TREE_NODES = 400
        private const val BATCH_FOREGROUND_CHECK_MS = 500L
        private const val BATCH_PAGE_SETTLE_MS = 1_200L
        private const val NEXT_PAGE_X_FRACTION = 0.86f
        private const val NEXT_PAGE_Y_FRACTION = 0.50f
        private const val NEXT_PAGE_TAP_DURATION_MS = 50L
        private const val FINGERPRINT_WIDTH = 32
        private const val FINGERPRINT_HEIGHT = 32
        private const val MAX_FINGERPRINT_AVERAGE_DIFFERENCE = 1.0
        private const val END_OF_BOOK_PERCENT = 99
        private const val LOW_TAP_X_FRACTION = 0.93f
        private const val LOW_TAP_Y_FRACTION = 0.80f
        private const val SWIPE_START_X_FRACTION = 0.85f
        private const val SWIPE_END_X_FRACTION = 0.15f
        private const val SWIPE_DURATION_MS = 250L
        private const val MAX_GESTURE_RETRIES = 3
        private const val GESTURE_RETRY_DELAY_MS = 1_000L
        private const val PROMPT_RESUME_DELAY_MS = 600L
        private const val TABLE_PREVIEW_LINES = 4
        private const val MAX_CONSECUTIVE_BLANK_PAGES = 10

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

        /**
         * @param pageLimit maximum screens to capture, or null to scan until the end of the book.
         * @param continuing true to add to previously captured text, skipping a repeat of the last saved page.
         */
        fun startBatch(pageLimit: Int?, continuing: Boolean, test: Boolean = false): Boolean {
            val service = activeService ?: return false
            if (!ProbeLog.isActive || (pageLimit != null && pageLimit < 1)) return false
            service.mainHandler.removeCallbacks(service.batchRunnable)
            service.batchPageLimit = pageLimit
            service.batchPagesCompleted = 0
            service.batchContinuing = continuing
            service.batchAwaitingFirstCapture = true
            if (!continuing) service.lastBatchFingerprint = null
            service.unchangedPageAttempts = 0
            service.consecutiveBlankPages = 0
            service.blankPagesSkipped = 0
            service.tablesSkipped = 0
            service.tablePolicy = ScanSettings.tableMode(service)
            service.lastProgressPercent = null
            service.gestureRetries = 0
            service.hidePrompt()
            service.batchIsTest = test
            service.batchTarget = if (test) OcrTextStore.test else OcrTextStore.main
            service.batchActive = true
            service.batchStatus = "Batch armed: waiting for Kindle to be foreground."
            val scope = pageLimit?.let { "up to $it screens" } ?: "the rest of the book"
            val mode = when {
                test -> "test scan"
                continuing -> "continuing previous scan"
                else -> "new scan"
            }
            ProbeLog.appendDiagnostic("BATCH OCR", "Batch armed for $scope ($mode); waiting for Kindle.")
            service.mainHandler.post(service.batchRunnable)
            return true
        }

        fun stopBatch() {
            activeService?.let { service ->
                if (service.batchActive) service.finishBatch("Batch stopped by user after ${service.batchPagesCompleted} screens.")
            }
        }

        fun batchStatus(): String = activeService?.batchStatus ?: "Accessibility service is not connected."
    }
}
