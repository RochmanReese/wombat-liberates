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
import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.view.accessibility.AccessibilityWindowInfo
import android.os.Vibrator
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    // New and continued scans wait for the user to tap "Start here" in Kindle: Kindle often opens a book at
    // its own idea of the start (chapter 1, past the contents) or the last-read page, not where the user wants.
    private var batchAwaitingStartTap = false
    // "First chapter only": stop when a second chapter opening is reached (that screen is not saved).
    private var stopAtNextChapter = false
    private var chapterStartsSeen = 0
    private var startPromptShown = false
    private var consecutiveBlankPages = 0
    private var blankPagesSkipped = 0
    private val pageLineHeights = mutableListOf<Int>()
    /** The book (or test scan) this scan writes into. */
    private var batchWorkspace: ScanWorkspace? = null
    private var tablesSkipped = 0
    private var tablePolicy = ScanSettings.TableMode.ASK
    private var lastProgressPercent: Int? = null
    private var gestureRetries = 0
    /** True between our page-turn gesture and the next capture, so leaving Kindle can be blamed on that gesture. */
    private var turnInFlight = false
    private var foregroundMisses = 0
    private var backPresses = 0
    private var savePictures = true

    // ---- Calls and notifications ----
    /** When the last notification was posted (uptime ms); a banner may be covering Kindle for a few seconds. */
    @Volatile
    private var lastNotificationAt = 0L
    /** When the screenshot for the current capture was requested, to discard captures a banner or call spoiled. */
    private var captureStartedAt = 0L
    /** What the scan is currently waiting out, so the log and status are written once per pause. */
    private var pausedFor: Interruption? = null
    private var callEndedAt = 0L
    private var callPromptShown = false
    /** Height of the system window at the top of the screen when the scan started (status bar, no banner). */
    private var baselineTopSystemHeight = 0
    private var bannerWaitStartedAt = 0L

    private enum class Interruption { CALL, NOTIFICATION }
    /** Set once the user picks a cover during this scan, so the cover question stops. */
    private var coverChosen = false
    private var promptView: View? = null
    private var batchStatus = "No batch running."
    private var lastBatchFingerprint: IntArray? = null
    private var unchangedPageAttempts = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ocrExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val batchRunnable: Runnable = Runnable {
        if (!batchActive || !ProbeLog.isActive) return@Runnable
        // Calls and notification banners come first: never press Back during a call, never read a banner.
        if (waitingOutInterruption()) return@Runnable
        if (!isKindleForeground()) {
            if (turnInFlight) handleLeftKindle() else mainHandler.postDelayed(batchRunnable, BATCH_FOREGROUND_CHECK_MS)
            return@Runnable
        }
        if (batchAwaitingStartTap) {
            if (!startPromptShown) showStartPrompt()
            mainHandler.postDelayed(batchRunnable, BATCH_FOREGROUND_CHECK_MS)
            return@Runnable
        }
        if (backPresses > 0) ProbeLog.appendDiagnostic("BATCH OCR", "Back in Kindle; continuing.")
        turnInFlight = false
        foregroundMisses = 0
        backPresses = 0
        captureBatchPage()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // If the app was stopped mid-scan, don't leave Do Not Disturb switched on.
        ScanDoNotDisturb.restoreAfterScan(applicationContext)
        PersistentProbeLog.initialize(applicationContext)
        activeService = this
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        mainHandler.removeCallbacks(batchRunnable)
        hidePrompt()
        ScanDoNotDisturb.restoreAfterScan(applicationContext)
        textRecognizer.close()
        ocrExecutor.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            // A notification or toast from another app: its banner may cover Kindle for a few seconds.
            if (event.packageName?.toString() != packageName) lastNotificationAt = SystemClock.uptimeMillis()
            return
        }
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
                val blocks = ocrBlocks(result)
                val pictures = if (onComplete != null && savePictures) findPictures(bitmap, blocks) else emptyList()
                bitmap.recycle()
                val page = PageLayout.analyze(blocks, imageHeight, pictures.map { it.first })
                val text = page.text
                if (onComplete != null) {
                    // Batch scans decide what to do with blank pages, tables, pictures and Kindle's end screen.
                    onComplete(OcrCapture(page, fingerprint, pictures.map { it.second }))
                } else if (page.wordsOnly.isBlank()) {
                    ProbeLog.appendDiagnostic("ONE-PAGE OCR RESULT", "No text recognized.")
                } else {
                    Workspaces.test(this).raw.appendPage(text)
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

    /** Asks the user to choose the starting page in Kindle before anything is captured. */
    private fun showStartPrompt() {
        startPromptShown = true
        val (title, message) = if (batchContinuing) {
            "Find where the last scan stopped" to "Turn Kindle to the page where the last scan stopped, then tap Start here. " +
                "If you land on the last page that was saved, that's fine: it won't be added twice."
        } else {
            "Choose the first page" to "Turn Kindle to the first page you want in the book, such as the title page or " +
                "contents. Kindle often opens at chapter 1 or where you last read. Then tap Start here."
        }
        ProbeLog.appendDiagnostic("BATCH OCR", "Kindle is open; waiting for the user to tap Start here.")
        batchStatus = "Waiting for you to choose the starting page in Kindle."
        showPrompt(
            title = title,
            message = message,
            rows = listOf(
                listOf(
                    "Start here" to {
                        if (!isKindleForeground()) {
                            Toast.makeText(this, "Open Kindle first, then tap Start here.", Toast.LENGTH_SHORT).show()
                        } else {
                            batchAwaitingStartTap = false
                            hidePrompt()
                            ProbeLog.appendDiagnostic("BATCH OCR", "User tapped Start here; scanning.")
                            batchStatus = "Batch running."
                            mainHandler.removeCallbacks(batchRunnable)
                            // Give the panel time to disappear so it isn't in the first screenshot.
                            mainHandler.postDelayed(batchRunnable, PROMPT_RESUME_DELAY_MS)
                        }
                    },
                    "Cancel" to { cancelBeforeStart() },
                ),
            ),
        )
    }

    /** Cancel from the start panel: nothing was scanned, so keep the book's previous scan summary as it was. */
    private fun cancelBeforeStart() {
        batchActive = false
        batchAwaitingStartTap = false
        mainHandler.removeCallbacks(batchRunnable)
        hidePrompt()
        ScanDoNotDisturb.restoreAfterScan(this)
        batchStatus = "Scan cancelled before the first page."
        ProbeLog.appendDiagnostic("BATCH OCR", batchStatus)
        val workspace = batchWorkspace
        // A brand-new book with no text yet would just be an empty entry in Your books.
        if (workspace != null && !batchContinuing && !workspace.isTest && !workspace.hasText()) {
            Workspaces.library(this).delete(workspace.key)
            batchWorkspace = null
        }
    }

    private fun captureBatchPage() {
        hidePrompt()
        captureStartedAt = SystemClock.uptimeMillis()
        val screenNumber = batchPagesCompleted + 1
        ProbeLog.appendDiagnostic("BATCH OCR", "Capturing ${screenLabel(screenNumber)}.")
        captureOnePageForOcr { capture -> mainHandler.post { handleBatchCapture(capture, screenNumber) } }
    }

    private fun handleBatchCapture(capture: OcrCapture?, screenNumber: Int) {
        if (!batchActive) return
        // A call or a notification during the capture may have put its text on the screenshot: take it again.
        if (isCallActive() || (lastNotificationAt >= captureStartedAt && !bannerWaitExpired())) {
            ProbeLog.appendDiagnostic("BATCH OCR", "A call or notification appeared during ${screenLabel(screenNumber)}; capturing it again.")
            mainHandler.postDelayed(batchRunnable, INTERRUPTION_CHECK_MS)
            return
        }
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
        page.bodyLineHeight?.let { pageLineHeights += it }
        when {
            page.wordsOnly.isBlank() && page.pictureCount == 0 -> {
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
                handleTablePage(page, capture, screenNumber)
            }
            else -> {
                consecutiveBlankPages = 0
                if (stopAtNextChapter && ChapterDetector.isChapterStartPage(page.text)) {
                    chapterStartsSeen += 1
                    if (chapterStartsSeen >= 2) {
                        batchPagesCompleted -= 1  // this screen belongs to the next chapter and is not saved
                        finishBatch("Batch complete: first chapter scanned (stopped at the start of the next chapter) after $batchPagesCompleted screens.")
                        return
                    }
                    ProbeLog.appendDiagnostic("BATCH OCR", "First chapter starts on ${screenLabel(screenNumber)}.")
                }
                if (stopAtNextChapter && batchPagesCompleted >= FIRST_CHAPTER_MAX_SCREENS) {
                    finishBatch("Batch stopped: no next chapter heading found within $FIRST_CHAPTER_MAX_SCREENS screens.")
                    return
                }
                val (text, pictureIds) = storePictures(page, capture)
                savePage(text, screenNumber, pictures = pictureIds.size)
                askAboutCover(pictureIds) { advance() }
            }
        }
    }

    /** Saves the page's pictures into the book and swaps the placeholders in its text for their markers. */
    private fun storePictures(page: PageAnalysis, capture: OcrCapture): Pair<String, List<String>> {
        val workspace = batchWorkspace ?: return Illustrations.removeMarkers(page.text) { false } to emptyList()
        val ids = capture.pictures.map { workspace.saveIllustration(it) }
        var text = page.text
        ids.forEachIndexed { index, id -> text = text.replace(PageLayout.picturePlaceholder(index), Illustrations.marker(id)) }
        return text to ids
    }

    /**
     * Near the start of a new scan, offer each picture as the book's cover until one is chosen.
     * [then] runs once the question is answered (or straight away if there is nothing to ask).
     */
    private fun askAboutCover(pictureIds: List<String>, then: () -> Unit) {
        val workspace = batchWorkspace
        val id = pictureIds.firstOrNull()
        if (workspace == null || id == null || coverChosen || batchContinuing ||
            batchPagesCompleted > COVER_QUESTION_SCREENS || workspace.coverImageId != null
        ) {
            then()
            return
        }
        val preview = workspace.illustrationFile(id)?.let { BitmapFactory.decodeFile(it.path) }
        ProbeLog.appendDiagnostic("BATCH OCR", "Picture near the start of the book; asking whether it is the cover.")
        showPrompt(
            title = "Use this picture as the book cover?",
            message = "Found on screen $batchPagesCompleted. The cover is shown in your reader's library and not repeated inside the book.",
            image = preview,
            rows = listOf(
                listOf(
                    "Use as cover" to {
                        workspace.coverImageId = id
                        coverChosen = true
                        ProbeLog.appendDiagnostic("BATCH OCR", "Picture $id chosen as the cover.")
                        hidePrompt()
                        mainHandler.postDelayed({ if (batchActive) then() }, PROMPT_RESUME_DELAY_MS)
                    },
                    "Not the cover" to {
                        hidePrompt()
                        mainHandler.postDelayed({ if (batchActive) askAboutCover(pictureIds.drop(1), then) }, PROMPT_RESUME_DELAY_MS)
                    },
                ),
                listOf("Stop scan" to { finishBatch("Batch stopped by user after $batchPagesCompleted screens.") }),
            ),
        )
    }

    /**
     * Finds pictures on the screenshot and returns each area (in screenshot pixels) with a JPEG of it.
     * Runs on the OCR thread, before the screenshot is recycled.
     */
    private fun findPictures(bitmap: Bitmap, blocks: List<List<OcrLine>>): List<Pair<Box, ByteArray>> = runCatching {
        val smallWidth = DETECTION_WIDTH
        val scale = bitmap.width.toFloat() / smallWidth
        val smallHeight = (bitmap.height / scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, smallWidth, smallHeight, true)
        val pixels = IntArray(smallWidth * smallHeight).also { small.getPixels(it, 0, smallWidth, 0, 0, smallWidth, smallHeight) }
        small.recycle()
        val textBoxes = blocks.flatten().map { line ->
            Box((line.left / scale).toInt(), (line.top / scale).toInt(), (line.right / scale).toInt() + 1, (line.bottom / scale).toInt() + 1)
        }
        IllustrationDetector.detect(pixels, smallWidth, smallHeight, textBoxes).map { box ->
            val area = Box(
                left = (box.left * scale).toInt().coerceIn(0, bitmap.width - 1),
                top = (box.top * scale).toInt().coerceIn(0, bitmap.height - 1),
                right = (box.right * scale).toInt().coerceIn(1, bitmap.width),
                bottom = (box.bottom * scale).toInt().coerceIn(1, bitmap.height),
            )
            val crop = Bitmap.createBitmap(bitmap, area.left, area.top, area.width, area.height)
            val jpeg = ByteArrayOutputStream().use { out ->
                crop.compress(Bitmap.CompressFormat.JPEG, PICTURE_JPEG_QUALITY, out)
                out.toByteArray()
            }
            crop.recycle()
            area to jpeg
        }
    }.getOrElse { error ->
        ProbeLog.appendDiagnostic("BATCH OCR", "Picture detection failed: ${error.message ?: error.javaClass.simpleName}")
        emptyList()
    }

    private fun savePage(text: String, screenNumber: Int, kind: String = "", pictures: Int = 0) {
        batchWorkspace?.raw?.appendPage(text)
        val pictureNote = when (pictures) {
            0 -> ""
            1 -> ", 1 picture"
            else -> ", $pictures pictures"
        }
        ProbeLog.appendDiagnostic("BATCH OCR", "Saved ${kind}${screenLabel(screenNumber)}: ${Illustrations.removeMarkers(text) { false }.length} characters$pictureNote.")
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

    /** True while the phone is ringing or a call (phone or internet call) is in progress. No permission needed. */
    private fun isCallActive(): Boolean = when (getSystemService(AudioManager::class.java)?.mode) {
        AudioManager.MODE_RINGTONE, AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> true
        else -> false
    }

    /** Height of the tallest system window touching the top of the screen (status bar, or status bar plus banner). */
    private fun topSystemWindowHeight(): Int = runCatching {
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
            .map { window -> Rect().also(window::getBoundsInScreen) }
            .filter { it.top <= 0 }
            .maxOfOrNull { it.height() } ?: 0
    }.getOrDefault(0)

    /** A notification banner (or the pulled-down shade) is covering the top of Kindle. */
    private fun isBannerShowing(): Boolean {
        val now = SystemClock.uptimeMillis()
        val recentNotification = now - lastNotificationAt < BANNER_WAIT_MS
        // The status bar window grows while a banner or the shade shows; compared with its size at the start.
        val grown = baselineTopSystemHeight > 0 && topSystemWindowHeight() > baselineTopSystemHeight + resources.displayMetrics.density * 24
        if (!recentNotification && !grown) {
            bannerWaitStartedAt = 0L
            return false
        }
        if (bannerWaitStartedAt == 0L) bannerWaitStartedAt = now
        if (bannerWaitExpired()) {
            // An app that keeps updating its notification, or an unusual phone layout, must never block the scan.
            if (pausedFor == Interruption.NOTIFICATION) {
                ProbeLog.appendDiagnostic("BATCH OCR", "Still seeing notification activity after ${MAX_BANNER_WAIT_MS / 1000}s; carrying on.")
            }
            return false
        }
        return true
    }

    private fun bannerWaitExpired(): Boolean =
        bannerWaitStartedAt != 0L && SystemClock.uptimeMillis() - bannerWaitStartedAt >= MAX_BANNER_WAIT_MS

    /**
     * Pauses the scan while a call or notification banner is in the way. Returns true if it is waiting
     * (and has scheduled the next check); false when the scan can carry on.
     */
    private fun waitingOutInterruption(): Boolean {
        if (isCallActive()) {
            if (pausedFor != Interruption.CALL) {
                pausedFor = Interruption.CALL
                batchStatus = "Paused for a phone call; the scan continues when the call ends."
                ProbeLog.appendDiagnostic("BATCH OCR", "Phone call; scan paused.")
            }
            callEndedAt = 0L
            mainHandler.postDelayed(batchRunnable, INTERRUPTION_CHECK_MS)
            return true
        }
        if (pausedFor == Interruption.CALL) {
            val now = SystemClock.uptimeMillis()
            if (callEndedAt == 0L) callEndedAt = now
            // Let the phone settle after the call (call screen closing, screen turning back on).
            if (now - callEndedAt < CALL_SETTLE_MS) {
                mainHandler.postDelayed(batchRunnable, INTERRUPTION_CHECK_MS)
                return true
            }
            // The call screen, not a link, took Kindle away: never press Back for it.
            turnInFlight = false
            if (!isKindleForeground()) {
                if (!callPromptShown) {
                    callPromptShown = true
                    ProbeLog.appendDiagnostic("BATCH OCR", "Call ended; waiting for Kindle to come back.")
                    showPrompt(
                        title = "Call finished",
                        message = "Switch back to Kindle (it should still be on the same page) and the scan will carry on by itself.",
                        rows = listOf(listOf("Stop scan" to { finishBatch("Batch stopped by user after $batchPagesCompleted screens.") })),
                    )
                }
                mainHandler.postDelayed(batchRunnable, BATCH_FOREGROUND_CHECK_MS)
                return true
            }
            pausedFor = null
            callPromptShown = false
            ProbeLog.appendDiagnostic("BATCH OCR", "Call ended; continuing the scan.")
        }
        if (isBannerShowing()) {
            if (pausedFor != Interruption.NOTIFICATION) {
                pausedFor = Interruption.NOTIFICATION
                ProbeLog.appendDiagnostic("BATCH OCR", "Notification banner on screen; waiting for it to clear.")
            }
            mainHandler.postDelayed(batchRunnable, INTERRUPTION_CHECK_MS)
            return true
        }
        if (pausedFor == Interruption.NOTIFICATION) pausedFor = null
        return false
    }

    /**
     * Something took the screen away from Kindle right after a page turn, usually a link that opened a
     * browser or another app. Press Back to return to Kindle; if that does not work, ask the user.
     */
    private fun handleLeftKindle() {
        foregroundMisses += 1
        if (foregroundMisses < FOREGROUND_MISSES_BEFORE_BACK) {
            mainHandler.postDelayed(batchRunnable, BATCH_FOREGROUND_CHECK_MS)
            return
        }
        foregroundMisses = 0
        if (backPresses < MAX_BACK_PRESSES) {
            backPresses += 1
            ProbeLog.appendDiagnostic("BATCH OCR", "A link or popup took the screen away from Kindle; pressing Back ($backPresses of $MAX_BACK_PRESSES).")
            performGlobalAction(GLOBAL_ACTION_BACK)
            mainHandler.postDelayed(batchRunnable, BACK_SETTLE_MS)
            return
        }
        ProbeLog.appendDiagnostic("BATCH OCR", "Could not get back to Kindle; asking the user.")
        showPrompt(
            title = "Scan paused",
            message = "A link opened another app and pressing Back didn't return to Kindle. Switch back to Kindle " +
                "(it should still be on the same page), then tap Continue. Everything scanned so far is saved.",
            rows = listOf(
                listOf("Continue" to {
                    hidePrompt()
                    turnInFlight = false
                    backPresses = 0
                    mainHandler.postDelayed(batchRunnable, PROMPT_RESUME_DELAY_MS)
                }),
                listOf("Stop scan" to { finishBatch("Batch stopped by user after $batchPagesCompleted screens.") }),
            ),
        )
    }

    /**
     * Kindle did not change the screen. Try other ways of turning the page first (a second swipe, then
     * taps in the right-hand margin, away from text and links). Then finish if Kindle's footer says the book is done, otherwise ask the user.
     */
    private fun handleStuckPage() {
        when (unchangedPageAttempts) {
            1 -> {
                ProbeLog.appendDiagnostic("BATCH OCR", "Kindle screen did not advance; swiping again.")
                turnToNextPage(PageTurn.SWIPE)
            }
            2 -> {
                ProbeLog.appendDiagnostic("BATCH OCR", "Kindle screen still did not advance; tapping the right-hand margin.")
                turnToNextPage(PageTurn.EDGE_TAP)
            }
            3 -> {
                ProbeLog.appendDiagnostic("BATCH OCR", "Kindle screen still did not advance; tapping lower in the right-hand margin.")
                turnToNextPage(PageTurn.EDGE_TAP_LOW)
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
                    turnInFlight = false
                    mainHandler.postDelayed(batchRunnable, PROMPT_RESUME_DELAY_MS)
                }),
                listOf("This is the end of the book" to {
                    finishBatch("Batch complete: end of book confirmed by you after $batchPagesCompleted screens.")
                }),
                listOf("Stop scan" to { finishBatch("Batch stopped by user after $batchPagesCompleted screens.") }),
            ),
        )
    }

    private fun handleTablePage(page: PageAnalysis, capture: OcrCapture, screenNumber: Int) {
        fun keep() {
            val (text, pictureIds) = storePictures(page, capture)
            savePage(text, screenNumber, kind = "table on ", pictures = pictureIds.size)
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
        val lastSaved = Illustrations.removeMarkers(batchWorkspace?.raw?.lastPageText().orEmpty()) { false }.trim()
        return lastSaved.isNotBlank() && lastSaved == capture.page.wordsOnly.trim()
    }

    /**
     * SWIPE is the normal page turn: unlike a tap it never follows a link or footnote. Margin taps are
     * fallbacks for when Kindle ignores the swipe.
     */
    private enum class PageTurn { SWIPE, EDGE_TAP, EDGE_TAP_LOW }

    private fun turnToNextPage(kind: PageTurn = PageTurn.SWIPE) {
        if (isCallActive()) {
            // Don't swipe while the phone rings or a call is in progress; turn the page once it's over.
            if (pausedFor != Interruption.CALL) {
                pausedFor = Interruption.CALL
                batchStatus = "Paused for a phone call; the scan continues when the call ends."
                ProbeLog.appendDiagnostic("BATCH OCR", "Phone call; pausing before the page turn.")
            }
            mainHandler.postDelayed({ if (batchActive) turnToNextPage(kind) }, INTERRUPTION_CHECK_MS)
            return
        }
        if (pausedFor == Interruption.CALL) {
            pausedFor = null
            ProbeLog.appendDiagnostic("BATCH OCR", "Call ended; continuing.")
        }
        if (!isKindleForeground()) {
            finishBatch("Batch stopped: Kindle is no longer in the foreground.")
            return
        }
        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        val path = Path()
        val duration = when (kind) {
            PageTurn.EDGE_TAP -> {
                path.moveTo(width * EDGE_TAP_X_FRACTION, height * NEXT_PAGE_Y_FRACTION)
                NEXT_PAGE_TAP_DURATION_MS
            }
            PageTurn.EDGE_TAP_LOW -> {
                path.moveTo(width * EDGE_TAP_X_FRACTION, height * LOW_TAP_Y_FRACTION)
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
                    turnInFlight = true
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
        ScanDoNotDisturb.restoreAfterScan(this)
        val tables = if (tablesSkipped > 0) " $tablesSkipped table pages skipped." else ""
        batchStatus = message + tables
        ProbeLog.appendDiagnostic("BATCH OCR", batchStatus)
        val workspace = batchWorkspace
        if (wasActive && workspace != null) {
            workspace.scanSummary = ScanSummary(
                screensRequested = batchPageLimit ?: 0,
                screensCaptured = batchPagesCompleted,
                blankPagesSkipped = blankPagesSkipped,
                stopReason = batchStatus,
                finishedAtMillis = System.currentTimeMillis(),
                lineHeightPx = TextSize.median(pageLineHeights),
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
    private fun showPrompt(title: String, message: String, rows: List<List<Pair<String, () -> Unit>>>, image: Bitmap? = null) {
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
        if (image != null) {
            panel.addView(
                ImageView(context).apply {
                    setImageBitmap(image)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (PROMPT_IMAGE_HEIGHT_DP * density).toInt()),
            )
        }
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

    private data class OcrCapture(val page: PageAnalysis, val fingerprint: IntArray, val pictures: List<ByteArray> = emptyList())

    companion object {
        private const val KINDLE_PACKAGE = "com.amazon.kindle"
        private const val MAX_TREE_NODES = 400
        private const val BATCH_FOREGROUND_CHECK_MS = 500L
        private const val BATCH_PAGE_SETTLE_MS = 1_200L
        private const val EDGE_TAP_X_FRACTION = 0.97f
        private const val NEXT_PAGE_Y_FRACTION = 0.50f
        private const val NEXT_PAGE_TAP_DURATION_MS = 50L
        private const val FINGERPRINT_WIDTH = 32
        private const val FINGERPRINT_HEIGHT = 32
        private const val MAX_FINGERPRINT_AVERAGE_DIFFERENCE = 1.0
        private const val END_OF_BOOK_PERCENT = 99
        private const val LOW_TAP_Y_FRACTION = 0.70f
        private const val FOREGROUND_MISSES_BEFORE_BACK = 3
        private const val MAX_BACK_PRESSES = 3
        private const val BACK_SETTLE_MS = 1_500L
        private const val SWIPE_START_X_FRACTION = 0.85f
        private const val SWIPE_END_X_FRACTION = 0.15f
        private const val SWIPE_DURATION_MS = 250L
        private const val MAX_GESTURE_RETRIES = 3
        private const val GESTURE_RETRY_DELAY_MS = 1_000L
        private const val PROMPT_RESUME_DELAY_MS = 600L
        private const val FIRST_CHAPTER_MAX_SCREENS = 300
        private const val TABLE_PREVIEW_LINES = 4
        private const val INTERRUPTION_CHECK_MS = 1_000L
        /** Notification banners usually stay up about 5 seconds. */
        private const val BANNER_WAIT_MS = 6_000L
        private const val MAX_BANNER_WAIT_MS = 30_000L
        private const val CALL_SETTLE_MS = 2_000L
        private const val COVER_QUESTION_SCREENS = 10
        private const val DETECTION_WIDTH = 72
        private const val PICTURE_JPEG_QUALITY = 85
        private const val PROMPT_IMAGE_HEIGHT_DP = 200
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
         * @param workspaceKey the library book (or [Workspaces.TEST]) to scan into.
         * @param pageLimit maximum screens to capture, or null to scan until the end of the book.
         * @param continuing true to add to previously captured text, skipping a repeat of the last saved page.
         */
        fun startBatch(workspaceKey: String, pageLimit: Int?, continuing: Boolean, firstChapterOnly: Boolean = false): Boolean {
            val service = activeService ?: return false
            if (!ProbeLog.isActive || (pageLimit != null && pageLimit < 1)) return false
            val workspace = Workspaces.byKey(service, workspaceKey) ?: return false
            val test = workspace.isTest
            service.mainHandler.removeCallbacks(service.batchRunnable)
            service.batchPageLimit = pageLimit
            service.batchPagesCompleted = 0
            service.batchContinuing = continuing
            service.batchAwaitingFirstCapture = true
            service.batchAwaitingStartTap = !test  // a test scan can start on any page
            service.startPromptShown = false
            service.stopAtNextChapter = firstChapterOnly
            service.chapterStartsSeen = 0
            // The remembered last screen only helps when continuing the same book.
            if (!continuing || service.batchWorkspace?.key != workspace.key) service.lastBatchFingerprint = null
            service.unchangedPageAttempts = 0
            service.consecutiveBlankPages = 0
            service.blankPagesSkipped = 0
            service.pageLineHeights.clear()
            service.tablesSkipped = 0
            service.tablePolicy = ScanSettings.tableMode(service)
            service.lastProgressPercent = null
            service.gestureRetries = 0
            service.turnInFlight = false
            service.foregroundMisses = 0
            service.backPresses = 0
            service.savePictures = ScanSettings.saveIllustrations(service)
            service.coverChosen = false
            service.pausedFor = null
            service.callEndedAt = 0L
            service.callPromptShown = false
            service.bannerWaitStartedAt = 0L
            service.baselineTopSystemHeight = service.topSystemWindowHeight()
            if (ScanDoNotDisturb.enableForScan(service)) ProbeLog.appendDiagnostic("BATCH OCR", "Do Not Disturb turned on for the scan.")
            service.hidePrompt()
            service.batchWorkspace = workspace
            service.batchActive = true
            service.batchStatus = "Batch armed: waiting for Kindle to be foreground."
            val scope = when {
                firstChapterOnly -> "the first chapter"
                pageLimit != null -> "up to $pageLimit screens"
                else -> "the rest of the book"
            }
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
