package com.techwombat.liberates

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.techwombat.liberates.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val book by lazy { Workspaces.book(this) }
    private val logListener: (String) -> Unit = { log ->
        runOnUiThread { binding.logText.text = log }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PersistentProbeLog.initialize(applicationContext)
        OcrTextStore.initialize(applicationContext)
        OcrRulesStore.initialize(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        renderOcrCorrections()
        binding.epubTitle.setText(book.title)
        binding.epubAuthor.setText(book.author)
        OllamaCredentialsStore.load(applicationContext)?.let { credentials ->
            binding.ollamaBaseUrl.setText(credentials.baseUrl)
            binding.ollamaModel.setText(credentials.model)
            binding.ollamaUsername.setText(credentials.username)
            binding.ollamaPassword.setText(credentials.password)
        }

        val saveRawText = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) writeTextTo(uri, OcrTextStore.rawFile(), "Raw OCR text saved")
        }
        val saveEpub = registerForActivityResult(ActivityResultContracts.CreateDocument("application/epub+zip")) { uri ->
            if (uri != null) exportEpub(uri)
        }
        val importRawText = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Could not open the selected file.")
                }.onSuccess { text ->
                    runCatching { OcrTextStore.replaceRawText(text) }
                        .onSuccess {
                            book.scanSummary = null
                            binding.statusText.text = "Raw text imported. Open Review to check the device-cleaned version."
                            Toast.makeText(this, "Imported ${text.length} characters", Toast.LENGTH_LONG).show()
                            renderBookQuality()
                        }
                        .onFailure { binding.statusText.text = "Import failed: ${it.message ?: "invalid text file"}" }
                }.onFailure {
                    binding.statusText.text = "Import failed: ${it.message ?: "could not read file"}"
                }
            }
        }

        binding.startButton.setOnClickListener {
            ProbeLog.start()
            renderState()
            if (!isAccessibilityServiceEnabled()) showAccessibilitySetupDialog()
        }
        binding.stopButton.setOnClickListener {
            KindleAccessibilityProbeService.stopBatch()
            ProbeLog.stop()
            renderState()
        }
        binding.startBookCaptureButton.setOnClickListener {
            val limitText = binding.pageCount.text.toString().trim()
            val pageLimit = limitText.toIntOrNull()
            if (limitText.isNotEmpty() && (pageLimit == null || pageLimit < 1)) {
                binding.statusText.text = "Screen limit must be a whole number of 1 or more, or blank to scan to the end of the book."
            } else if (OcrTextStore.hasRawText()) {
                promptContinueOrNewBook(pageLimit)
            } else {
                promptForBookTitle(pageLimit, continuing = false)
            }
        }
        binding.startTestScanButton.setOnClickListener {
            val testPages = binding.testPageCount.text.toString().trim().toIntOrNull()
            if (testPages == null || testPages !in TEST_SCAN_PAGES) {
                binding.statusText.text = "Test screens must be between ${TEST_SCAN_PAGES.first} and ${TEST_SCAN_PAGES.last}."
            } else {
                startTestScan(testPages)
            }
        }
        binding.viewTestResultsButton.setOnClickListener {
            if (OcrTextStore.test.hasRawText()) {
                startActivity(ReviewActivity.intent(this, Workspaces.TEST))
            } else {
                binding.statusText.text = "No test scan yet. Start a test scan first."
            }
        }
        binding.stopBatchButton.setOnClickListener {
            KindleAccessibilityProbeService.stopBatch()
            renderState()
        }
        binding.helpButton.setOnClickListener { startActivity(Intent(this, HelpActivity::class.java)) }

        binding.advancedControlsButton.setOnClickListener {
            val showAdvanced = binding.advancedControls.visibility != View.VISIBLE
            binding.advancedControls.visibility = if (showAdvanced) View.VISIBLE else View.GONE
            binding.advancedControlsButton.text = if (showAdvanced) "Hide advanced settings and diagnostics" else "Show advanced settings and diagnostics"
        }

        binding.addOcrCorrectionButton.setOnClickListener { addOcrCorrection() }
        binding.resetOcrCorrectionsButton.setOnClickListener { confirmResetOcrCorrections() }

        binding.importRawTextButton.setOnClickListener {
            importRawText.launch(arrayOf("text/plain", "text/*"))
        }
        binding.reviewBookButton.setOnClickListener {
            saveTitleAndAuthor()
            startActivity(ReviewActivity.intent(this, Workspaces.BOOK))
        }
        binding.saveOllamaSettingsButton.setOnClickListener { saveOllamaSettings() }
        binding.tableModeGroup.check(
            when (ScanSettings.tableMode(this)) {
                ScanSettings.TableMode.ASK -> R.id.tableModeAsk
                ScanSettings.TableMode.KEEP -> R.id.tableModeKeep
                ScanSettings.TableMode.SKIP -> R.id.tableModeSkip
            },
        )
        binding.tableModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.tableModeKeep -> ScanSettings.TableMode.KEEP
                R.id.tableModeSkip -> ScanSettings.TableMode.SKIP
                else -> ScanSettings.TableMode.ASK
            }
            ScanSettings.setTableMode(this, mode)
        }
        binding.saveRawTextButton.setOnClickListener { saveRawText.launch(exportFileName("kindle-ocr")) }
        binding.exportEpubButton.setOnClickListener {
            saveTitleAndAuthor()
            if (!book.hasText()) {
                binding.statusText.text = "Scan or import a book before creating an EPUB."
            } else {
                saveEpub.launch(epubFileName(book.title.ifBlank { "Untitled" }))
            }
        }
        binding.clearLogButton.setOnClickListener {
            ProbeLog.clear()
            book.clearAll()
            Workspaces.test(this).clearAll()
            renderBookQuality()
            binding.statusText.text = "Book scan, test scan, all cleaned versions, and diagnostics cleared."
            Toast.makeText(this, "Captured text and diagnostics cleared", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        ProbeLog.addListener(logListener)
        renderState()
        renderBookQuality()
    }

    override fun onPause() {
        saveTitleAndAuthor()
        super.onPause()
    }

    override fun onStop() {
        ProbeLog.removeListener(logListener)
        super.onStop()
    }

    private fun startBatch(pageLimit: Int?, continuing: Boolean) {
        if (!ProbeLog.isActive) ProbeLog.start()
        if (!isAccessibilityServiceEnabled()) {
            binding.statusText.text = "Enable Wombat Liberates in Accessibility settings, then start the scan again."
            showAccessibilitySetupDialog()
            return
        }
        if (KindleAccessibilityProbeService.startBatch(pageLimit, continuing)) {
            val scope = pageLimit?.let { "up to $it screens" } ?: "until the end of the book"
            binding.statusText.text = if (continuing) {
                "Scan will continue $scope; open Kindle at the page where the last scan stopped."
            } else {
                "Book scan ready $scope; open Kindle at the starting page."
            }
        } else {
            binding.statusText.text = "Enable Wombat Liberates in Accessibility settings first."
        }
    }

    private fun startTestScan(pageCount: Int) {
        if (!ProbeLog.isActive) ProbeLog.start()
        if (!isAccessibilityServiceEnabled()) {
            binding.statusText.text = "Enable Wombat Liberates in Accessibility settings, then start the test scan again."
            showAccessibilitySetupDialog()
            return
        }
        Workspaces.test(this).clearAll()
        if (KindleAccessibilityProbeService.startBatch(pageCount, continuing = false, test = true)) {
            binding.statusText.text = "Test scan ready for $pageCount screens; open Kindle at any page with ordinary text. Results open automatically when it finishes."
        } else {
            binding.statusText.text = "Enable Wombat Liberates in Accessibility settings first."
        }
    }

    private fun promptContinueOrNewBook(pageLimit: Int?) {
        AlertDialog.Builder(this)
            .setTitle("Continue previous scan?")
            .setMessage("Captured text from an earlier scan is still saved. Continue adds new pages after it (the last saved page is skipped if Kindle is still showing it). New book clears the captured and cleaned text first.")
            .setPositiveButton("Continue scan") { _, _ -> startBatch(pageLimit, continuing = true) }
            .setNeutralButton("New book") { _, _ -> promptForBookTitle(pageLimit, continuing = false, clearFirst = true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptForBookTitle(pageLimit: Int?, continuing: Boolean, clearFirst: Boolean = false) {
        val titleInput = EditText(this).apply {
            hint = "Book title"
            setText(binding.epubTitle.text)
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("What book are you scanning?")
            .setMessage("This name is shown during cleanup and used for the EPUB. You can change it later.")
            .setView(titleInput)
            .setPositiveButton("Start scan") { _, _ ->
                binding.epubTitle.setText(titleInput.text.toString().trim())
                if (clearFirst) book.clearAll()
                saveTitleAndAuthor()
                startBatch(pageLimit, continuing)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderOcrCorrections() {
        val rules = OcrRulesStore.customRules(applicationContext)
        binding.ocrCorrectionsList.removeAllViews()
        if (rules.isEmpty()) {
            binding.ocrCorrectionsList.addView(TextView(this).apply {
                text = "No custom corrections added. Default corrections are active."
                textSize = 12f
            })
            return
        }

        rules.forEachIndexed { index, rule ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4, 0, 4)
            }
            row.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "${rule.find} → ${rule.replace.ifEmpty { "(empty)" }}"
                textSize = 14f
            })
            row.addView(Button(this).apply {
                text = "Delete"
                setOnClickListener {
                    OcrRulesStore.removeCustomRule(applicationContext, index)
                    renderOcrCorrections()
                    binding.statusText.text = "Custom OCR correction removed."
                }
            })
            binding.ocrCorrectionsList.addView(row)
        }
    }

    private fun addOcrCorrection() {
        val find = binding.ocrCorrectionFind.text.toString().trim()
        val replace = binding.ocrCorrectionReplace.text.toString().trim()
        if (find.isEmpty()) {
            binding.statusText.text = "Find text cannot be empty."
            return
        }
        val existingRules = OcrRulesParser.parse(OcrRulesStore.readRules(applicationContext)).rules
        if (existingRules.any { it.find == find }) {
            binding.statusText.text = "A correction for '$find' already exists."
            return
        }

        OcrRulesStore.addCustomRule(applicationContext, OcrLiteralRule(find, replace))
        binding.ocrCorrectionFind.text?.clear()
        binding.ocrCorrectionReplace.text?.clear()
        renderOcrCorrections()
        binding.statusText.text = "Custom OCR correction added."
    }

    private fun confirmResetOcrCorrections() {
        AlertDialog.Builder(this)
            .setTitle("Reset custom corrections?")
            .setMessage("This removes only your added OCR corrections and restores the defaults. Raw OCR, cleaned OCR, diagnostics, and Ollama settings are unchanged.")
            .setPositiveButton("Reset") { _, _ ->
                OcrRulesStore.resetToDefaults(applicationContext)
                renderOcrCorrections()
                binding.statusText.text = "OCR corrections restored to defaults."
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveTitleAndAuthor() {
        book.title = binding.epubTitle.text.toString().trim()
        book.author = binding.epubAuthor.text.toString().trim()
    }

    private fun saveOllamaSettings() {
        val baseUrl = binding.ollamaBaseUrl.text.toString().trim().trimEnd('/')
        val model = binding.ollamaModel.text.toString().trim()
        val username = binding.ollamaUsername.text.toString().trim()
        val password = binding.ollamaPassword.text.toString()
        if (!baseUrl.startsWith("https://") || model.isBlank() || username.isBlank() || password.isBlank()) {
            binding.statusText.text = "Enter an HTTPS Ollama URL, model, username, and password."
            return
        }
        OllamaCredentialsStore.save(applicationContext, OllamaCredentialsStore.Credentials(baseUrl, model, username, password))
        binding.statusText.text = "Ollama settings saved. Use them from the review screen when device OCR is not good enough."
    }

    /** Shows the quality check for the book's device-cleaned text on the main screen. */
    private fun renderBookQuality() {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val device = book.ensureDeviceVersion(currentRules()) ?: return@withContext null
                OcrQuality.describe(OcrQuality.assess(book.text(device))).lineSequence().first()
            }
            binding.bookQualityText.text = text ?: "No book text yet."
        }
    }

    private fun currentRules(): List<OcrLiteralRule> =
        OcrRulesParser.parse(OcrRulesStore.readRules(applicationContext)).takeIf { it.isValid }?.rules.orEmpty()

    private fun renderState() {
        binding.statusText.text = if (ProbeLog.isActive) {
            "Scanning service ready. ${KindleAccessibilityProbeService.batchStatus()}"
        } else {
            "Ready to scan or import."
        }
        binding.startButton.isEnabled = !ProbeLog.isActive
        binding.stopButton.isEnabled = ProbeLog.isActive
        binding.logText.text = ProbeLog.snapshot()
    }

    private fun exportEpub(destination: Uri) {
        val title = book.title.ifBlank { "Untitled" }
        val author = book.author.ifBlank { "Unknown author" }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val version = book.preferredVersion(currentRules()) ?: error("No book text to export.")
                    contentResolver.openOutputStream(destination)?.use { EpubWriter.write(it, book.text(version), title, author) }
                        ?: error("Could not create the EPUB file.")
                    version.label
                }
            }
            result.onSuccess { label ->
                binding.statusText.text = "EPUB saved using $label."
                Toast.makeText(this@MainActivity, "EPUB saved", Toast.LENGTH_LONG).show()
            }.onFailure { exception ->
                binding.statusText.text = "EPUB export failed: ${exception.message ?: "unknown error"}"
                Toast.makeText(this@MainActivity, "EPUB export failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun epubFileName(title: String): String {
        val safe = title.map { character ->
            if (character.isLetterOrDigit() || character == '_' || character == '-') character else '_'
        }.joinToString("")
        return "${safe.ifBlank { "book" }}.epub"
    }

    private fun exportFileName(defaultName: String): String {
        val entered = binding.exportFileName.text.toString().trim().ifBlank { defaultName }
        val safeName = entered.map { character ->
            if (character.isLetterOrDigit() || character == '.' || character == '_' || character == '-') character else '_'
        }.joinToString("")
        return if (safeName.endsWith(".txt", ignoreCase = true)) safeName else "$safeName.txt"
    }

    private fun writeTextTo(destination: Uri, source: java.io.File, message: String) {
        contentResolver.openOutputStream(destination)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.contains(packageName + "/" + KindleAccessibilityProbeService::class.java.name, ignoreCase = true)
    }

    private fun showAccessibilitySetupDialog() {
        AlertDialog.Builder(this)
            .setTitle("Turn on Wombat Liberates")
            .setMessage("Wombat Liberates needs Android Accessibility access to read Kindle pages during a scan. Android will now open Accessibility settings. Scroll down to Wombat Liberates, tap it, then turn on Allow.")
            .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private companion object {
        val TEST_SCAN_PAGES = 10..100
    }
}
