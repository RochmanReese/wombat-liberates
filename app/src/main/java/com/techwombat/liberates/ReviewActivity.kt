package com.techwombat.liberates

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.techwombat.liberates.databinding.ActivityReviewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Review a scan (book or test): OCR quality, every saved version, a paged viewer, a side-by-side
 * comparison, saving/opening as text or EPUB, and optional AI cleanup when device OCR is not good enough.
 */
class ReviewActivity : AppCompatActivity() {
    private lateinit var binding: ActivityReviewBinding
    private lateinit var workspace: ScanWorkspace
    private var versions: List<TextVersion> = emptyList()
    private var aiJob: Job? = null
    private var pendingSave: TextVersion? = null
    private var viewerChunks: List<String> = emptyList()
    private var viewerPage = 0
    private var qualityReport: OcrQuality.Report? = null
    private val geminiModel by lazy { Generation.getClient() }

    private val saveText = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val version = pendingSave
        if (uri != null && version != null) writeToUri(uri, "Text saved") { output -> output.write(workspace.text(version).toByteArray()) }
    }
    private val saveEpub = registerForActivityResult(ActivityResultContracts.CreateDocument("application/epub+zip")) { uri ->
        val version = pendingSave
        if (uri != null && version != null) {
            writeToUri(uri, "EPUB saved") { output -> BookExport.writeEpub(output, workspace, workspace.text(version), epubTitle(version)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OcrRulesStore.initialize(applicationContext)
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        workspace = Workspaces.byKey(this, intent.getStringExtra(EXTRA_WORKSPACE)) ?: run {
            Toast.makeText(this, "That book no longer exists.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.reviewTitle.text = reviewTitle()
        binding.saveToDocumentsButton.visibility = if (workspace.isTest) View.GONE else View.VISIBLE
        binding.saveToDocumentsButton.setOnClickListener { saveToDocuments() }
        binding.closeReviewButton.setOnClickListener { finish() }
        binding.compareButton.setOnClickListener { compareSelected() }
        binding.viewerPreviousButton.setOnClickListener { showViewerPage(viewerPage - 1) }
        binding.viewerNextButton.setOnClickListener { showViewerPage(viewerPage + 1) }
        binding.viewerCloseButton.setOnClickListener { binding.viewerSection.visibility = View.GONE }
        binding.ollamaButton.setOnClickListener { startOllama() }
        binding.geminiButton.setOnClickListener { startGemini() }
        binding.aiStopButton.setOnClickListener { aiJob?.cancel() }

        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (aiJob?.isActive != true) {
            workspace = Workspaces.byKey(this, intent.getStringExtra(EXTRA_WORKSPACE)) ?: return
            binding.reviewTitle.text = reviewTitle()
            binding.saveToDocumentsButton.visibility = if (workspace.isTest) View.GONE else View.VISIBLE
            refresh()
        }
    }

    override fun onDestroy() {
        aiJob?.cancel()
        super.onDestroy()
    }

    private fun reviewTitle(): String = if (workspace.isTest) "Test scan review" else "Book review"

    // ---- Loading ----

    /** Regenerates the device version if needed, then redraws everything. */
    private fun refresh() {
        binding.reviewSubtitle.text = if (workspace.isTest) workspace.title else BookExport.title(workspace)
        binding.scanSummaryText.text = "Loading…"
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                if (!workspace.hasText()) return@withContext null
                val parsed = OcrRulesParser.parse(OcrRulesStore.readRules(applicationContext))
                val device = workspace.ensureDeviceVersion(if (parsed.isValid) parsed.rules else emptyList())
                val report = device?.let { OcrQuality.assess(workspace.text(it)) }
                Triple(workspace.versions(), report, parsed.takeUnless { it.isValid }?.errors?.firstOrNull()?.message)
            }
            if (loaded == null) {
                showEmpty()
                return@launch
            }
            val (savedVersions, report, rulesError) = loaded
            versions = listOf(workspace.rawVersion()) + savedVersions
            qualityReport = report
            binding.scanSummaryText.text = summaryText(report)
            binding.qualityText.text = buildString {
                append(report?.let(OcrQuality::describe) ?: "No text to check.")
                if (rulesError != null) append("\n\nYour OCR rules file has an invalid entry, so device cleanup used no custom rules: $rulesError")
            }
            renderVersions()
            renderSavedEpub()
            renderIllustrations()
            renderCompareChoices()
            renderImproveSection()
        }
    }

    private fun showEmpty() {
        versions = emptyList()
        binding.illustrationsList.removeAllViews()
        binding.illustrationsSummaryText.text = ""
        binding.scanSummaryText.text = summaryText(null) + "\n\nNo text has been captured yet."
        binding.qualityText.text = "No text to check."
        binding.versionsList.removeAllViews()
        binding.compareButton.isEnabled = false
        binding.ollamaButton.isEnabled = false
        binding.geminiButton.isEnabled = false
        binding.improveAdviceText.text = ""
    }

    private fun summaryText(report: OcrQuality.Report?): String {
        val words = report?.let { "${"%,d".format(it.words)} words captured." }.orEmpty()
        val summary = workspace.scanSummary ?: return words
        val requested = if (summary.screensRequested > 0) " of ${summary.screensRequested}" else ""
        val blanks = if (summary.blankPagesSkipped > 0) " ${summary.blankPagesSkipped} screens had no text and were skipped." else ""
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(summary.finishedAtMillis))
        return "Last scan: ${summary.screensCaptured}$requested screens, finished $time.$blanks $words\n${summary.stopReason}".trim()
    }

    // ---- Versions list ----

    private fun renderVersions() {
        val signature = workspace.rawSignature()
        val preferredId = workspace.preferredVersionId ?: versions.firstOrNull { it.kind == VersionKind.DEVICE }?.id
        binding.versionsList.removeAllViews()
        versions.forEach { version ->
            val details = mutableListOf<String>()
            details += DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(version.createdAtMillis))
            if (version.seconds > 0) details += formatDuration(version.seconds)
            if (!version.complete) details += "unfinished: ${workspace.chunkCount(version)} of ${version.expectedChunks} chunks"
            if (version.kind != VersionKind.RAW && version.kind != VersionKind.EARLIER && version.rawSignature != signature) {
                details += "made before the scan text last changed"
            }
            val usedForEpub = !workspace.isTest && version.id == preferredId
            val button = Button(this).apply {
                isAllCaps = false
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                text = "${version.label}${if (usedForEpub) "  ✓ used for EPUB" else ""}\n${details.joinToString(" · ")}"
                setOnClickListener { showVersionActions(version) }
            }
            binding.versionsList.addView(button)
        }
    }

    private fun showVersionActions(version: TextVersion) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions += "View" to { openViewer(version) }
        actions += "Save as text…" to {
            pendingSave = version
            saveText.launch(fileName(version, "txt"))
        }
        actions += "Save as EPUB…" to {
            pendingSave = version
            saveEpub.launch(fileName(version, "epub"))
        }
        actions += "Open in EPUB reader…" to { openInReader(version) }
        if (!workspace.isTest && version.complete) {
            actions += "Use this version for the EPUB" to {
                workspace.preferredVersionId = version.id
                renderVersions()
                renderSavedEpub()
                Toast.makeText(this, "The EPUB will use ${version.label}", Toast.LENGTH_SHORT).show()
            }
        }
        if (version.kind == VersionKind.OLLAMA || version.kind == VersionKind.GEMINI || version.kind == VersionKind.EARLIER) {
            actions += "Delete this version" to { confirmDelete(version) }
        }
        AlertDialog.Builder(this)
            .setTitle(version.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(version: TextVersion) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${version.label}?")
            .setMessage("Raw OCR and other versions are not affected.")
            .setPositiveButton("Delete") { _, _ ->
                workspace.delete(version)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Illustrations ----

    private fun renderIllustrations() {
        lifecycleScope.launch {
            val coverId = workspace.coverImageId
            val thumbnails = withContext(Dispatchers.IO) {
                workspace.illustrationIds().mapNotNull { id -> workspace.illustrationFile(id)?.let { file -> id to decodeThumbnail(file) } }
            }
            binding.illustrationsList.removeAllViews()
            binding.illustrationsSummaryText.text = when {
                thumbnails.isEmpty() -> "No pictures saved from this scan."
                else -> "${thumbnails.size} pictures saved. Tap one to view it, use it as the cover, or delete it." +
                    if (coverId == null) " No cover chosen." else ""
            }
            val density = resources.displayMetrics.density
            thumbnails.chunked(THUMBNAILS_PER_ROW).forEach { row ->
                val rowLayout = android.widget.LinearLayout(this@ReviewActivity).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
                row.forEach { (id, bitmap) ->
                    val cell = android.widget.LinearLayout(this@ReviewActivity).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                        setOnClickListener { showIllustrationActions(id) }
                    }
                    cell.addView(
                        android.widget.ImageView(this@ReviewActivity).apply {
                            setImageBitmap(bitmap)
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            contentDescription = if (id == coverId) "Cover picture" else "Illustration $id"
                        },
                        android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (THUMBNAIL_HEIGHT_DP * density).toInt()),
                    )
                    cell.addView(android.widget.TextView(this@ReviewActivity).apply {
                        text = if (id == coverId) "Cover" else id.substringBeforeLast('.')
                        textSize = 12f
                        textAlignment = View.TEXT_ALIGNMENT_CENTER
                    })
                    rowLayout.addView(cell, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }
                // Keep the last row's thumbnails the same size as the others.
                repeat(THUMBNAILS_PER_ROW - row.size) {
                    rowLayout.addView(View(this@ReviewActivity), android.widget.LinearLayout.LayoutParams(0, 1, 1f))
                }
                binding.illustrationsList.addView(rowLayout)
            }
        }
    }

    private fun decodeThumbnail(file: File): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMBNAIL_DECODE_WIDTH) sample *= 2
        return android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun showIllustrationActions(id: String) {
        val isCover = workspace.coverImageId == id
        val actions = listOf<Pair<String, () -> Unit>>(
            "View larger" to { showLargeIllustration(id) },
            (if (isCover) "Stop using as cover" else "Use as book cover") to {
                workspace.coverImageId = if (isCover) null else id
                refreshAfterPictureChange()
            },
            "Delete picture" to { confirmDeleteIllustration(id) },
        )
        AlertDialog.Builder(this)
            .setTitle(if (isCover) "Cover picture" else "Picture ${id.substringBeforeLast('.')}")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLargeIllustration(id: String) {
        val file = workspace.illustrationFile(id) ?: return
        val image = android.widget.ImageView(this).apply {
            setImageBitmap(android.graphics.BitmapFactory.decodeFile(file.path))
            adjustViewBounds = true
        }
        AlertDialog.Builder(this).setView(image).setPositiveButton("Close", null).show()
    }

    private fun confirmDeleteIllustration(id: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete this picture?")
            .setMessage("It is removed from the book and from future EPUBs. The text is not changed.")
            .setPositiveButton("Delete") { _, _ ->
                workspace.deleteIllustration(id)
                refreshAfterPictureChange()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshAfterPictureChange() {
        renderIllustrations()
        renderSavedEpub()
    }

    // ---- Viewer ----

    private fun openViewer(version: TextVersion) {
        viewerChunks = workspace.chunks(version).ifEmpty { listOf("(This version has no text yet.)") }
        binding.viewerTitle.text = version.label
        binding.viewerSection.visibility = View.VISIBLE
        showViewerPage(0)
        binding.reviewScroll.post { binding.reviewScroll.smoothScrollTo(0, binding.viewerSection.top) }
    }

    private fun showViewerPage(page: Int) {
        val pages = (viewerChunks.size + CHUNKS_PER_VIEWER_PAGE - 1) / CHUNKS_PER_VIEWER_PAGE
        viewerPage = page.coerceIn(0, (pages - 1).coerceAtLeast(0))
        val from = viewerPage * CHUNKS_PER_VIEWER_PAGE
        binding.viewerText.text = Illustrations.readable(
            viewerChunks.subList(from, minOf(from + CHUNKS_PER_VIEWER_PAGE, viewerChunks.size)).joinToString("\n\n"),
            workspace.coverImageId,
        )
        binding.viewerPageText.text = "Part ${viewerPage + 1} of $pages"
        binding.viewerPreviousButton.isEnabled = viewerPage > 0
        binding.viewerNextButton.isEnabled = viewerPage < pages - 1
    }

    // ---- Compare ----

    private fun comparableVersions(): List<TextVersion> {
        val signature = workspace.rawSignature()
        return versions.filter { it.kind == VersionKind.RAW || it.comparableWith(signature) }
    }

    private fun renderCompareChoices() {
        val choices = comparableVersions()
        val labels = choices.map { it.label }
        listOf(binding.compareFromSpinner, binding.compareToSpinner).forEach { spinner ->
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        }
        binding.compareButton.isEnabled = choices.size >= 2
        if (choices.size >= 2) {
            // Default: the most recent AI version against device cleanup, otherwise raw against device cleanup.
            val device = choices.indexOfFirst { it.kind == VersionKind.DEVICE }.coerceAtLeast(0)
            val latestAi = choices.indexOfLast { it.kind == VersionKind.OLLAMA || it.kind == VersionKind.GEMINI }
            binding.compareFromSpinner.setSelection(if (latestAi >= 0) device else 0)
            binding.compareToSpinner.setSelection(if (latestAi >= 0) latestAi else device)
        }
        binding.compareSummaryText.text = if (choices.size < 2) "Nothing to compare yet." else ""
        binding.compareDiffText.text = ""
    }

    private fun compareSelected() {
        val choices = comparableVersions()
        val from = choices.getOrNull(binding.compareFromSpinner.selectedItemPosition) ?: return
        val to = choices.getOrNull(binding.compareToSpinner.selectedItemPosition) ?: return
        if (from.id == to.id) {
            binding.compareSummaryText.text = "Pick two different versions."
            binding.compareDiffText.text = ""
            return
        }
        binding.compareButton.isEnabled = false
        binding.compareSummaryText.text = "Comparing…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { buildComparison(workspace.chunks(from), workspace.chunks(to), from.label, to.label) }
            binding.compareSummaryText.text = result.first
            binding.compareDiffText.text = result.second
            binding.compareButton.isEnabled = true
        }
    }

    private fun buildComparison(before: List<String>, after: List<String>, fromLabel: String, toLabel: String): Pair<String, CharSequence> {
        if (before.size != after.size) return "These versions were split differently and cannot be compared." to ""
        val rendered = SpannableStringBuilder()
        var removed = 0
        var added = 0
        var regions = 0
        var heavyChunks = 0
        var changedParagraphs = 0
        before.indices.forEach { index ->
            val pieces = WordDiff.diff(before[index], after[index])
            val summary = WordDiff.summarize(pieces)
            removed += summary.removedWords
            added += summary.addedWords
            regions += summary.changeRegions
            val chunkWords = WordDiff.tokenize(before[index]).count { it != WordDiff.PARAGRAPH }.coerceAtLeast(1)
            if (summary.removedWords + summary.addedWords > chunkWords * HEAVY_CHANGE_FRACTION) heavyChunks++
            WordDiff.changedParagraphs(pieces).forEach { paragraph ->
                if (changedParagraphs++ < MAX_SHOWN_PARAGRAPHS) {
                    if (rendered.isNotEmpty()) rendered.append("\n\n")
                    appendParagraph(rendered, paragraph)
                }
            }
        }
        if (removed == 0 && added == 0) return "No differences between $fromLabel and $toLabel." to ""
        val summary = buildString {
            append("From $fromLabel to $toLabel: $regions changes in $changedParagraphs paragraphs; $removed words removed, $added added.")
            if (changedParagraphs > MAX_SHOWN_PARAGRAPHS) append(" Showing the first $MAX_SHOWN_PARAGRAPHS changed paragraphs.")
            if (heavyChunks > 0) {
                append("\n⚠ $heavyChunks of ${before.size} chunks changed more than ${(HEAVY_CHANGE_FRACTION * 100).toInt()}% of their words. This may be rewriting rather than fixing OCR mistakes.")
            }
        }
        return summary to rendered
    }

    private fun appendParagraph(builder: SpannableStringBuilder, paragraph: List<WordDiff.Piece>) {
        paragraph.forEachIndexed { index, piece ->
            if (index > 0) builder.append(' ')
            val start = builder.length
            builder.append(piece.text)
            val end = builder.length
            when (piece.kind) {
                WordDiff.Kind.REMOVED -> {
                    builder.setSpan(BackgroundColorSpan(REMOVED_COLOR), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                WordDiff.Kind.ADDED -> builder.setSpan(BackgroundColorSpan(ADDED_COLOR), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                WordDiff.Kind.SAME -> Unit
            }
        }
    }

    // ---- Improve with AI ----

    private fun renderImproveSection() {
        val report = qualityReport
        binding.improveAdviceText.text = when (report?.level) {
            OcrQuality.Level.POOR -> "Device OCR looks poor for this scan. Ollama cleanup is recommended: compare the result with device cleanup before using it."
            OcrQuality.Level.FAIR -> "Device OCR has a few problems. Adding your own OCR corrections may be enough; use Ollama only if they are not."
            OcrQuality.Level.GOOD -> "Device OCR looks good. AI cleanup is probably not needed."
            null -> ""
        }
        val credentials = OllamaCredentialsStore.load(applicationContext)
        binding.ollamaButton.isEnabled = true
        binding.geminiButton.isEnabled = true
        binding.ollamaButton.text = if (credentials == null) {
            "Clean with Ollama (set up in Advanced settings first)"
        } else {
            val resumable = workspace.resumableVersion(VersionKind.OLLAMA, credentials.model)
            if (resumable != null) {
                "Continue Ollama cleanup (${workspace.chunkCount(resumable)} of ${resumable.expectedChunks} done)"
            } else {
                "Clean with Ollama (${credentials.model})"
            }
        }
        val geminiResumable = workspace.resumableVersion(VersionKind.GEMINI, GEMINI_MODEL_LABEL)
        binding.geminiButton.text = if (geminiResumable != null) {
            "Continue on-device AI cleanup (${workspace.chunkCount(geminiResumable)} of ${geminiResumable.expectedChunks} done)"
        } else {
            "Clean with on-device AI (Gemini Nano)"
        }
    }

    private fun startOllama() {
        val credentials = OllamaCredentialsStore.load(applicationContext)
        if (credentials == null || !credentials.baseUrl.startsWith("https://") || credentials.model.isBlank()) {
            binding.aiProgressText.text = "Ollama is not set up. Enter an HTTPS URL, model, username and password under Ollama connection in Advanced settings, then tap Save Ollama settings."
            return
        }
        runAiCleanup(VersionKind.OLLAMA, credentials.model) { chunk ->
            withContext(Dispatchers.IO) { OllamaClient.correct(credentials, chunk) }
        }
    }

    private fun startGemini() {
        if (aiJob?.isActive == true) return
        lifecycleScope.launch {
            binding.aiProgressText.text = "Checking on-device AI…"
            when (geminiModel.checkStatus()) {
                FeatureStatus.UNAVAILABLE -> binding.aiProgressText.text = "On-device AI (Gemini Nano) is not available on this phone."
                FeatureStatus.DOWNLOADABLE -> {
                    binding.aiProgressText.text = "Downloading the on-device AI model; keep this screen open."
                    geminiModel.download().collect { }
                    runGemini()
                }
                FeatureStatus.AVAILABLE -> runGemini()
                else -> binding.aiProgressText.text = "On-device AI is not ready yet. Try again in a few minutes."
            }
        }
    }

    private fun runGemini() = runAiCleanup(VersionKind.GEMINI, GEMINI_MODEL_LABEL) { chunk ->
        geminiModel.generateContent(CorrectionPrompt.build(chunk)).candidates.firstOrNull()?.text.orEmpty()
    }

    /** Cleans each raw chunk with an AI model, saving every chunk as it finishes so the run can be continued later. */
    private fun runAiCleanup(kind: VersionKind, model: String, correct: suspend (String) -> String) {
        if (aiJob?.isActive == true) return
        setAiRunning(true)
        aiJob = lifecycleScope.launch {
            val rawChunks = withContext(Dispatchers.IO) { workspace.rawChunks() }
            var version = withContext(Dispatchers.IO) {
                workspace.resumableVersion(kind, model)?.takeIf { it.expectedChunks == rawChunks.size }
                    ?: workspace.startAiVersion(kind, model, rawChunks.size)
            }
            var done = workspace.chunkCount(version)
            var sessionStart = System.currentTimeMillis()
            binding.aiProgressBar.max = rawChunks.size
            try {
                while (done < rawChunks.size) {
                    binding.aiProgressBar.progress = done
                    binding.aiProgressText.text = "${version.label}: chunk ${done + 1} of ${rawChunks.size}…"
                    val corrected = Illustrations.restoreMarkers(rawChunks[done], correct(rawChunks[done]))
                    if (corrected.isBlank()) error("${kind.shortLabel} returned no text for chunk ${done + 1}.")
                    withContext(Dispatchers.IO) { workspace.appendChunk(version, corrected) }
                    done++
                    // Save elapsed time regularly so an interrupted run still records it.
                    if (done % 5 == 0 || done == rawChunks.size) {
                        val now = System.currentTimeMillis()
                        version = withContext(Dispatchers.IO) { workspace.updateProgress(version, (now - sessionStart) / 1000) }
                        sessionStart = now
                    }
                }
                binding.aiProgressBar.progress = done
                binding.aiProgressText.text = "${version.label} finished in ${formatDuration(version.seconds)}. Compare it with Device cleanup below."
            } catch (error: Throwable) {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    workspace.updateProgress(version, (System.currentTimeMillis() - sessionStart) / 1000)
                }
                binding.aiProgressText.text = if (error is CancellationException) {
                    "Stopped after $done of ${rawChunks.size} chunks. Tap the button again to continue."
                } else {
                    "${kind.shortLabel} stopped after $done of ${rawChunks.size} chunks: ${error.message ?: error.javaClass.simpleName}. Tap the button again to continue."
                }
                if (error is CancellationException && isDestroyed) throw error
            } finally {
                setAiRunning(false)
                if (!isDestroyed) refresh()
            }
        }
    }

    private fun setAiRunning(running: Boolean) {
        binding.aiProgressBar.visibility = if (running) View.VISIBLE else View.GONE
        binding.aiStopButton.visibility = if (running) View.VISIBLE else View.GONE
        binding.ollamaButton.isEnabled = !running
        binding.geminiButton.isEnabled = !running
    }

    // ---- Saving and opening ----

    private fun preferredId(): String? = workspace.preferredVersionId ?: versions.firstOrNull { it.kind == VersionKind.DEVICE }?.id

    private fun epubTitle(version: TextVersion): String = BookExport.epubTitle(workspace, version, preferredId())

    private fun fileName(version: TextVersion, extension: String): String =
        BookExport.safeFileName("${BookExport.title(workspace)} ${version.label}", extension)

    private fun currentRules(): List<OcrLiteralRule> =
        OcrRulesParser.parse(OcrRulesStore.readRules(applicationContext)).takeIf { it.isValid }?.rules.orEmpty()

    private fun renderSavedEpub() {
        if (workspace.isTest) {
            binding.savedEpubText.text = ""
            return
        }
        lifecycleScope.launch {
            val (saved, outOfDate) = withContext(Dispatchers.IO) { workspace.savedEpub to workspace.isSavedEpubOutOfDate(currentRules()) }
            binding.savedEpubText.text = if (saved == null) {
                "No EPUB saved yet. EPUBs are saved to Documents/${BookExport.FOLDER_NAME}."
            } else {
                val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(saved.builtAtMillis))
                "Saved as Documents/${BookExport.FOLDER_NAME}/${saved.fileName} from ${saved.versionLabel}, $time." +
                    if (outOfDate) "\nThe text has changed since: save again to update it." else ""
            }
            binding.saveToDocumentsButton.text = if (saved == null) "Save EPUB to Documents" else "Update EPUB in Documents"
        }
    }

    private fun saveToDocuments() {
        binding.saveToDocumentsButton.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { BookExport.saveToDocuments(applicationContext, workspace, currentRules()) } }
            binding.saveToDocumentsButton.isEnabled = true
            result.onSuccess { Toast.makeText(this@ReviewActivity, "Saved ${it.fileName}", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this@ReviewActivity, "Save failed: ${it.message}", Toast.LENGTH_LONG).show() }
            renderSavedEpub()
        }
    }

    private fun writeToUri(uri: Uri, message: String, write: (java.io.OutputStream) -> Unit) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openOutputStream(uri)?.use(write) ?: error("Could not create the file.") }
            }
            result.onSuccess { Toast.makeText(this@ReviewActivity, message, Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this@ReviewActivity, "Save failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun openInReader(version: TextVersion) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { BookExport.buildShareableEpub(applicationContext, workspace, version, preferredId()) } }
                .getOrElse {
                    Toast.makeText(this@ReviewActivity, "EPUB failed: ${it.message}", Toast.LENGTH_LONG).show()
                    return@launch
                }
            BookExport.openInReader(this@ReviewActivity, file)
        }
    }

    private fun formatDuration(seconds: Long): String =
        if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"

    companion object {
        const val EXTRA_WORKSPACE = "workspace"
        private const val CHUNKS_PER_VIEWER_PAGE = 8
        private const val THUMBNAILS_PER_ROW = 3
        private const val THUMBNAIL_HEIGHT_DP = 110
        private const val THUMBNAIL_DECODE_WIDTH = 320
        private const val MAX_SHOWN_PARAGRAPHS = 300
        private const val HEAVY_CHANGE_FRACTION = 0.2
        private const val GEMINI_MODEL_LABEL = "on-device"
        private val REMOVED_COLOR = Color.argb(0x66, 0xE5, 0x39, 0x35)
        private val ADDED_COLOR = Color.argb(0x66, 0x43, 0xA0, 0x47)

        fun intent(context: Context, workspaceKey: String): Intent =
            Intent(context, ReviewActivity::class.java).putExtra(EXTRA_WORKSPACE, workspaceKey)
    }
}
