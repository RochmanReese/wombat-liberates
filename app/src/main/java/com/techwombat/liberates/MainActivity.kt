package com.techwombat.liberates

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
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
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val library by lazy { Workspaces.library(this) }
    private val logListener: (String) -> Unit = { log ->
        runOnUiThread { binding.logText.text = log }
    }

    private val importRawText = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importTextFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PersistentProbeLog.initialize(applicationContext)
        OcrRulesStore.initialize(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        renderOcrCorrections()
        OllamaCredentialsStore.load(applicationContext)?.let { credentials ->
            binding.ollamaBaseUrl.setText(credentials.baseUrl)
            binding.ollamaModel.setText(credentials.model)
            binding.ollamaUsername.setText(credentials.username)
            binding.ollamaPassword.setText(credentials.password)
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
            pageLimitOrNull()?.let { limit -> promptForNewBook(limit.value) }
        }
        binding.stopBatchButton.setOnClickListener {
            KindleAccessibilityProbeService.stopBatch()
            renderState()
        }
        binding.importRawTextButton.setOnClickListener { importRawText.launch(arrayOf("text/plain", "text/*")) }
        binding.helpButton.setOnClickListener { startActivity(Intent(this, HelpActivity::class.java)) }

        binding.startTestScanButton.setOnClickListener {
            val testPages = binding.testPageCount.text.toString().trim().toIntOrNull()
            if (testPages == null || testPages !in TEST_SCAN_PAGES) {
                binding.statusText.text = "Test screens must be between ${TEST_SCAN_PAGES.first} and ${TEST_SCAN_PAGES.last}."
            } else {
                startTestScan(testPages)
            }
        }
        binding.viewTestResultsButton.setOnClickListener {
            if (Workspaces.test(this).hasText()) {
                startActivity(ReviewActivity.intent(this, Workspaces.TEST))
            } else {
                binding.statusText.text = "No test scan yet. Start a test scan first."
            }
        }

        binding.advancedControlsButton.setOnClickListener {
            val showAdvanced = binding.advancedControls.visibility != View.VISIBLE
            binding.advancedControls.visibility = if (showAdvanced) View.VISIBLE else View.GONE
            binding.advancedControlsButton.text = if (showAdvanced) "Hide advanced settings and diagnostics" else "Show advanced settings and diagnostics"
        }
        binding.addOcrCorrectionButton.setOnClickListener { addOcrCorrection() }
        binding.resetOcrCorrectionsButton.setOnClickListener { confirmResetOcrCorrections() }
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
        binding.saveIllustrationsCheck.isChecked = ScanSettings.saveIllustrations(this)
        binding.saveIllustrationsCheck.setOnCheckedChangeListener { _, checked -> ScanSettings.setSaveIllustrations(this, checked) }
        binding.doNotDisturbCheck.isChecked = ScanSettings.doNotDisturbWhileScanning(this)
        binding.doNotDisturbCheck.setOnCheckedChangeListener { _, checked ->
            ScanSettings.setDoNotDisturbWhileScanning(this, checked)
            if (checked && !ScanDoNotDisturb.hasPermission(this)) askForDoNotDisturbPermission()
            renderDoNotDisturbStatus()
        }
        binding.clearLogButton.setOnClickListener {
            ProbeLog.clear()
            binding.statusText.text = "Diagnostics cleared."
        }
    }

    override fun onStart() {
        super.onStart()
        ProbeLog.addListener(logListener)
        renderState()
        renderLibrary()
        renderDoNotDisturbStatus()
    }

    private fun askForDoNotDisturbPermission() {
        AlertDialog.Builder(this)
            .setTitle("Allow Do Not Disturb access")
            .setMessage("Android will open the Do Not Disturb access page. Find Wombat Liberates and turn it on, then come back.")
            .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun renderDoNotDisturbStatus() {
        val base = "Silences notification banners during a scan and turns back off when it ends. Your own Do Not Disturb rules decide which calls still ring. Calls and banners are handled either way: the scan pauses for a call and waits for banners to clear."
        binding.doNotDisturbStatusText.text = if (ScanSettings.doNotDisturbWhileScanning(this) && !ScanDoNotDisturb.hasPermission(this)) {
            "Permission needed: untick and tick the box again to open Android's Do Not Disturb access page. $base"
        } else {
            base
        }
    }

    override fun onStop() {
        ProbeLog.removeListener(logListener)
        super.onStop()
    }

    // ---- Library ----

    private data class BookRow(val workspace: ScanWorkspace, val quality: String?, val epubOutOfDate: Boolean, val pictures: Int)

    private fun renderLibrary() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val rules = currentRules()
                library.books().map { book -> BookRow(book, book.qualitySummary(rules), book.isSavedEpubOutOfDate(rules), book.illustrationIds().size) }
            }
            binding.bookList.removeAllViews()
            binding.emptyLibraryText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            rows.forEach { row -> binding.bookList.addView(bookButton(row)) }
        }
    }

    private fun bookButton(row: BookRow): Button {
        val book = row.workspace
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(book.lastActivityMillis()))
        val details = listOfNotNull(
            book.author.ifBlank { null },
            book.scanSummary?.let { "${it.screensCaptured} screens" },
            row.pictures.takeIf { it > 0 }?.let { if (it == 1) "1 picture" else "$it pictures" },
            date,
        ).joinToString(" · ")
        val saved = book.savedEpub
        val epub = when {
            saved == null -> "No EPUB saved yet"
            row.epubOutOfDate -> "EPUB saved, text changed since"
            else -> "EPUB saved"
        }
        val quality = row.quality ?: "No text yet"
        return Button(this).apply {
            isAllCaps = false
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            text = "${BookExport.title(book)}\n$details\n$quality\n$epub"
            setOnClickListener { showBookActions(book) }
        }
    }

    private fun showBookActions(book: ScanWorkspace) {
        val saved = book.savedEpub
        val actions = listOf<Pair<String, () -> Unit>>(
            "Review, compare and improve" to { startActivity(ReviewActivity.intent(this, book.key)) },
            "Continue scanning this book" to { pageLimitOrNull()?.let { startBatch(book, it.value, continuing = true) } },
            (if (saved == null) "Save EPUB to Documents" else "Update EPUB in Documents") to { saveEpub(book) },
            "Open in EPUB reader" to { openInReader(book) },
            "Edit title and author" to { editTitleAndAuthor(book) },
            "Delete this book" to { confirmDelete(book) },
        )
        AlertDialog.Builder(this)
            .setTitle(BookExport.title(book))
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveEpub(book: ScanWorkspace) {
        binding.statusText.text = "Saving EPUB…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { BookExport.saveToDocuments(applicationContext, book, currentRules()) } }
            result.onSuccess {
                binding.statusText.text = "Saved Documents/${BookExport.FOLDER_NAME}/${it.fileName} from ${it.versionLabel}."
                Toast.makeText(this@MainActivity, "EPUB saved", Toast.LENGTH_SHORT).show()
            }.onFailure {
                binding.statusText.text = "EPUB save failed: ${it.message ?: it.javaClass.simpleName}"
            }
            renderLibrary()
        }
    }

    private fun openInReader(book: ScanWorkspace) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val version = book.preferredVersion(currentRules()) ?: error("This book has no text yet.")
                    BookExport.buildShareableEpub(applicationContext, book, version, version.id)
                }
            }.getOrElse {
                binding.statusText.text = "Could not open the book: ${it.message}"
                return@launch
            }
            BookExport.openInReader(this@MainActivity, file)
        }
    }

    private fun editTitleAndAuthor(book: ScanWorkspace) {
        val (view, titleInput, authorInput) = titleAuthorFields(book.title, book.author)
        AlertDialog.Builder(this)
            .setTitle("Edit book details")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                book.title = titleInput.text.toString().trim()
                book.author = authorInput.text.toString().trim()
                renderLibrary()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(book: ScanWorkspace) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${BookExport.title(book)}?")
            .setMessage("The scanned text and all its cleaned versions are deleted. EPUBs already saved in Documents/${BookExport.FOLDER_NAME} are kept.")
            .setPositiveButton("Delete") { _, _ ->
                library.delete(book.key)
                binding.statusText.text = "Deleted ${BookExport.title(book)}."
                renderLibrary()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importTextFile(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Could not open the selected file.")
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }?.substringBeforeLast('.').orEmpty()
                    val book = library.create(name.ifBlank { "Imported text" }, "")
                    runCatching { book.raw.replaceRawText(text) }.onFailure {
                        library.delete(book.key)
                        throw it
                    }
                    book
                }
            }
            result.onSuccess {
                binding.statusText.text = "Imported as a new book: ${BookExport.title(it)}."
                renderLibrary()
            }.onFailure { binding.statusText.text = "Import failed: ${it.message ?: "could not read file"}" }
        }
    }

    // ---- Scanning ----

    /** Wraps the optional screen limit so "no limit" (null) can be told apart from "invalid input". */
    private data class PageLimit(val value: Int?)

    private fun pageLimitOrNull(): PageLimit? {
        val limitText = binding.pageCount.text.toString().trim()
        val pageLimit = limitText.toIntOrNull()
        if (limitText.isNotEmpty() && (pageLimit == null || pageLimit < 1)) {
            binding.statusText.text = "Screen limit must be a whole number of 1 or more, or blank to scan to the end of the book."
            return null
        }
        return PageLimit(pageLimit)
    }

    private fun promptForNewBook(pageLimit: Int?) {
        val (view, titleInput, authorInput) = titleAuthorFields("", "")
        AlertDialog.Builder(this)
            .setTitle("What book are you scanning?")
            .setMessage("A new book is added to your library. You can change these details later.")
            .setView(view)
            .setPositiveButton("Start scan") { _, _ ->
                if (!checkScanningReady()) return@setPositiveButton
                val book = library.create(titleInput.text.toString().trim(), authorInput.text.toString().trim())
                startBatch(book, pageLimit, continuing = false)
                renderLibrary()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun titleAuthorFields(title: String, author: String): Triple<View, EditText, EditText> {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val titleInput = EditText(this).apply {
            hint = "Book title"
            setText(title)
            setSingleLine()
        }
        val authorInput = EditText(this).apply {
            hint = "Author"
            setText(author)
            setSingleLine()
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(titleInput)
            addView(authorInput)
        }
        return Triple(layout, titleInput, authorInput)
    }

    /** Starts the scanning service if needed; returns false (and explains) if accessibility is off. */
    private fun checkScanningReady(): Boolean {
        if (!ProbeLog.isActive) ProbeLog.start()
        if (!isAccessibilityServiceEnabled()) {
            binding.statusText.text = "Enable Wombat Liberates in Accessibility settings, then start the scan again."
            showAccessibilitySetupDialog()
            return false
        }
        return true
    }

    private fun startBatch(book: ScanWorkspace, pageLimit: Int?, continuing: Boolean) {
        if (!checkScanningReady()) return
        if (KindleAccessibilityProbeService.startBatch(book.key, pageLimit, continuing)) {
            val scope = pageLimit?.let { "up to $it screens" } ?: "until the end of the book"
            binding.statusText.text = if (continuing) {
                "Continuing ${BookExport.title(book)} $scope; open Kindle at the page where the last scan stopped."
            } else {
                "Scanning ${BookExport.title(book)} $scope; open Kindle at the starting page."
            }
        } else {
            binding.statusText.text = "Enable Wombat Liberates in Accessibility settings first."
        }
    }

    private fun startTestScan(pageCount: Int) {
        if (!checkScanningReady()) return
        Workspaces.test(this).clearAll()
        if (KindleAccessibilityProbeService.startBatch(Workspaces.TEST, pageCount, continuing = false)) {
            binding.statusText.text = "Test scan ready for $pageCount screens; open Kindle at any page with ordinary text. Results open automatically when it finishes."
        } else {
            binding.statusText.text = "Enable Wombat Liberates in Accessibility settings first."
        }
    }

    // ---- OCR corrections and Ollama settings ----

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
            .setMessage("This removes only your added OCR corrections and restores the defaults. Books, cleaned versions, diagnostics and Ollama settings are unchanged.")
            .setPositiveButton("Reset") { _, _ ->
                OcrRulesStore.resetToDefaults(applicationContext)
                renderOcrCorrections()
                binding.statusText.text = "OCR corrections restored to defaults."
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        binding.statusText.text = "Ollama settings saved. Use them from a book's review screen when device OCR is not good enough."
    }

    private fun currentRules(): List<OcrLiteralRule> =
        OcrRulesParser.parse(OcrRulesStore.readRules(applicationContext)).takeIf { it.isValid }?.rules.orEmpty()

    // ---- Service state ----

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
