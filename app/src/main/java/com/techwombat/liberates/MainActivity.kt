package com.techwombat.liberates

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.techwombat.liberates.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val correctionModel by lazy { Generation.getClient() }
    private var correctionJob: Job? = null
    private val logListener: (String) -> Unit = { log ->
        runOnUiThread { binding.logText.text = log }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PersistentProbeLog.initialize(applicationContext)
        OcrTextStore.initialize(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
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

        val saveCorrectedText = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) writeTextTo(uri, OcrTextStore.correctedFile(), "Corrected OCR text saved")
        }
        val importRawText = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Could not open the selected file.")
                }.onSuccess { text ->
                    runCatching { OcrTextStore.replaceRawText(text) }
                        .onSuccess {
                            binding.statusText.text = "Raw text imported; corrected text cleared."
                            Toast.makeText(this, "Imported ${text.length} characters; corrected text cleared.", Toast.LENGTH_LONG).show()
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
            val pageCount = binding.pageCount.text.toString().toIntOrNull()
            if (pageCount == null || pageCount !in 1..1_000) {
                binding.statusText.text = "Enter a screen count between 1 and 1,000."
            } else {
                promptForBookTitle(pageCount)
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

        binding.importRawTextButton.setOnClickListener {
            importRawText.launch(arrayOf("text/plain", "text/*"))
        }
        binding.correctOnDeviceButton.setOnClickListener { startOnDeviceCorrection() }
        binding.quickLocalCleanupButton.setOnClickListener { startLocalCleanup() }
        binding.correctOllamaButton.setOnClickListener { startOllamaCorrection() }
        binding.saveRawTextButton.setOnClickListener { saveRawText.launch(exportFileName("kindle-ocr")) }
        binding.exportEpubButton.setOnClickListener {
            val title = binding.epubTitle.text.toString().trim().ifBlank { "Untitled" }
            saveEpub.launch(epubFileName(title))
        }

        binding.saveCorrectedTextButton.setOnClickListener {
            if (OcrTextStore.hasCorrectedText()) {
                saveCorrectedText.launch(exportFileName("kindle-ocr-corrected"))
            } else {
                Toast.makeText(this, "No corrected text yet", Toast.LENGTH_SHORT).show()
            }
        }
        binding.clearLogButton.setOnClickListener {
            ProbeLog.clear()
            OcrTextStore.clear()
            binding.statusText.text = "Raw OCR, corrected OCR, and diagnostics cleared."
            Toast.makeText(this, "Captured text and diagnostics cleared", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        ProbeLog.addListener(logListener)
        renderState()
    }

    override fun onStop() {
        ProbeLog.removeListener(logListener)
        super.onStop()
    }

    override fun onDestroy() {
        correctionJob?.cancel()
        super.onDestroy()
    }

    private fun startBatch(pageCount: Int) {
        if (!ProbeLog.isActive) ProbeLog.start()
        if (!isAccessibilityServiceEnabled()) {
            binding.statusText.text = "Enable Wombat Liberates in Accessibility settings, then start the scan again."
            showAccessibilitySetupDialog()
            return
        }
        if (KindleAccessibilityProbeService.startBatch(pageCount)) {
            binding.statusText.text = "Book scan ready for up to $pageCount screens; open Kindle at the starting page."
        } else {
            binding.statusText.text = "Enable Wombat Liberates in Accessibility settings first."
        }
    }

    private fun promptForBookTitle(pageCount: Int) {
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
                startBatch(pageCount)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun cleanupProgressLabel(): String {
        val title = binding.epubTitle.text.toString().trim()
        return if (title.isBlank()) "AI text cleanup" else "AI text cleanup — $title"
    }

    private fun startLocalCleanup() {
        if (correctionJob?.isActive == true) return
        val rawText = OcrTextStore.rawText()
        if (rawText.isBlank()) {
            binding.statusText.text = "Scan or import text before cleaning it."
            return
        }
        correctionJob = lifecycleScope.launch {
            runCorrection(rawText, cleanupProgressLabel()) { chunk ->
                var cleaned = chunk
                    .replace("4| ", "“I ")
                    .replace("|I ", "“I ")
                    .replace("yOu", "you")
                    .replace("yOur", "your")
                    .replace("YOu", "You")
                    .replace("YOur", "Your")
                    .replace(" 1...", " I...")
                    .replace("\n1...", "\nI...")
                cleaned = cleaned.mapIndexed { index, character ->
                    if (character.code != 124) return@mapIndexed character
                    val previous = cleaned.getOrNull(index - 1)
                    val next = cleaned.getOrNull(index + 1)
                    when {
                        previous?.isLetter() == true || previous?.code == 39 -> 108.toChar()
                        next?.isLetter() == true -> 73.toChar()
                        else -> character
                    }
                }.joinToString("")
                cleaned
            }
        }
    }


    private fun startOnDeviceCorrection() {
        if (correctionJob?.isActive == true) return
        val rawText = OcrTextStore.rawText()
        if (rawText.isBlank()) {
            binding.statusText.text = "Scan or import text before cleaning it."
            Toast.makeText(this, "Import or scan text first.", Toast.LENGTH_SHORT).show()
            return
        }
        correctionJob = lifecycleScope.launch {
            when (correctionModel.checkStatus()) {
                FeatureStatus.UNAVAILABLE -> {
                    binding.statusText.text = "On-device correction is unavailable on this phone."
                }
                FeatureStatus.DOWNLOADABLE -> {
                    binding.statusText.text = "Downloading the on-device correction model; keep this app open."
                    correctionModel.download().collect { }
                    runOnDeviceCorrection(rawText)
                }
                FeatureStatus.AVAILABLE -> runOnDeviceCorrection(rawText)
                else -> binding.statusText.text = "On-device correction is not ready yet."
            }
        }
    }

    private fun startOllamaCorrection() {
        if (correctionJob?.isActive == true) {
            binding.statusText.text = "A correction job is already running."
            Toast.makeText(this, "A correction job is already running.", Toast.LENGTH_SHORT).show()
            return
        }
        val rawText = OcrTextStore.rawText()
        val baseUrl = binding.ollamaBaseUrl.text.toString().trim().trimEnd('/')
        val model = binding.ollamaModel.text.toString().trim()
        val username = binding.ollamaUsername.text.toString().trim()
        val password = binding.ollamaPassword.text.toString()
        if (rawText.isBlank()) {
            binding.statusText.text = "Scan or import text before cleaning it."
            Toast.makeText(this, "Import or scan text first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!baseUrl.startsWith("https://") || model.isBlank() || username.isBlank() || password.isBlank()) {
            binding.statusText.text = "Enter an HTTPS Ollama URL, model, username, and password."
            Toast.makeText(this, "Ollama connection details are incomplete.", Toast.LENGTH_SHORT).show()
            return
        }
        binding.statusText.text = "Connecting to Ollama…"
        binding.correctionProgressBar.visibility = View.VISIBLE
        binding.correctionProgressText.text = "Connecting to Ollama…"
        Toast.makeText(this, "Connecting to Ollama…", Toast.LENGTH_SHORT).show()
        OllamaCredentialsStore.save(applicationContext, OllamaCredentialsStore.Credentials(baseUrl, model, username, password))
        correctionJob = lifecycleScope.launch {
            runOllamaCorrection(rawText, baseUrl, model, username, password)
        }
    }

    private suspend fun runOnDeviceCorrection(rawText: String) {
        runCorrection(rawText, cleanupProgressLabel()) { chunk ->
            correctionModel.generateContent(correctionPrompt(chunk)).candidates.firstOrNull()?.text.orEmpty()
        }
    }

    private suspend fun runOllamaCorrection(
        rawText: String,
        baseUrl: String,
        model: String,
        username: String,
        password: String,
    ) {
        try {
            runCorrection(rawText, cleanupProgressLabel()) { chunk ->
                withContext(Dispatchers.IO) {
                    requestOllamaCorrection(baseUrl, model, username, password, correctionPrompt(chunk))
                }
            }
        } finally {
            // Password is held in activity memory only; its persisted copy is Android Keystore-encrypted.
        }
    }

    private suspend fun runCorrection(
        rawText: String,
        source: String,
        correctChunk: suspend (String) -> String,
    ) {
        val chunks = chunkForCorrection(rawText)
        binding.correctionProgressBar.visibility = View.VISIBLE
        binding.correctionProgressBar.max = chunks.size
        binding.correctionProgressBar.progress = 0
        binding.correctionProgressText.text = "$source: 0 of ${chunks.size} chunks complete; ${chunks.size} remaining."
        OcrTextStore.beginCorrectedText()
        binding.correctOnDeviceButton.isEnabled = false
        binding.correctOllamaButton.isEnabled = false
        binding.quickLocalCleanupButton.isEnabled = false
        try {
            chunks.forEachIndexed { index, chunk ->
                binding.statusText.text = "$source: chunk ${index + 1} of ${chunks.size}."
                binding.correctionProgressText.text = "$source: sending chunk ${index + 1} of ${chunks.size}; ${chunks.size - index} remaining."
                val corrected = correctChunk(chunk)
                if (corrected.isBlank()) error("The correction model returned no text.")
                OcrTextStore.appendCorrectedChunk(corrected)
                binding.correctionProgressBar.progress = index + 1
                binding.correctionProgressText.text = "$source: ${index + 1} of ${chunks.size} chunks complete; ${chunks.size - index - 1} remaining."
            }
            binding.statusText.text = "$source complete: ${chunks.size} chunks saved separately."
            binding.correctionProgressText.text = "$source complete: ${chunks.size} of ${chunks.size} chunks saved."
            Toast.makeText(this, "$source complete.", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            binding.statusText.text = "$source stopped: ${error.message ?: error.javaClass.simpleName}. Raw OCR is unchanged."
            binding.correctionProgressText.text = "$source stopped after ${binding.correctionProgressBar.progress} of ${chunks.size} chunks: ${error.message ?: error.javaClass.simpleName}"
            Toast.makeText(this, "$source failed: ${error.message ?: error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        } finally {
            binding.correctOnDeviceButton.isEnabled = true
            binding.correctOllamaButton.isEnabled = true
            binding.quickLocalCleanupButton.isEnabled = true
        }
    }

    private fun requestOllamaCorrection(
        baseUrl: String,
        model: String,
        username: String,
        password: String,
        prompt: String,
    ): String {
        val connection = (URL("$baseUrl/api/generate").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = OLLAMA_CONNECT_TIMEOUT_MS
            readTimeout = OLLAMA_READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            val credentials = "$username:$password".toByteArray(Charsets.UTF_8)
            setRequestProperty("Authorization", "Basic ${Base64.encodeToString(credentials, Base64.NO_WRAP)}")
        }
        return try {
            val body = JSONObject()
                .put("model", model)
                .put("prompt", prompt)
                .put("stream", false)
                .put("options", JSONObject().put("temperature", 0.1))
                .toString()
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val responseBody = if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (connection.responseCode !in 200..299) error("Ollama HTTP ${connection.responseCode}: ${responseBody.take(300)}")
            JSONObject(responseBody).optString("response").trim()
        } finally {
            connection.disconnect()
        }
    }

    private fun chunkForCorrection(text: String): List<String> {
        val paragraphs = text.split(Regex("\\n\\s*\\n")).map(String::trim).filter(String::isNotBlank)
        val chunks = mutableListOf<String>()
        var chunk = ""
        paragraphs.forEach { paragraph ->
            if (paragraph.length > CORRECTION_CHUNK_CHAR_LIMIT) {
                if (chunk.isNotBlank()) {
                    chunks += chunk
                    chunk = ""
                }
                paragraph.chunked(CORRECTION_CHUNK_CHAR_LIMIT).forEach(chunks::add)
            } else if (chunk.isBlank()) {
                chunk = paragraph
            } else if (chunk.length + paragraph.length + 2 <= CORRECTION_CHUNK_CHAR_LIMIT) {
                chunk += "\n\n$paragraph"
            } else {
                chunks += chunk
                chunk = paragraph
            }
        }
        if (chunk.isNotBlank()) chunks += chunk
        return chunks
    }

    private fun correctionPrompt(chunk: String): String = """
        You are a conservative OCR proofreader.

        Correct only unmistakable OCR mistakes: confused characters, capitalization errors, broken words, stray punctuation, and word-break errors.
        Preserve the author's exact wording, sentence order, paragraph breaks, dialogue, chapter headings, names, formatting, and meaning.
        Do not rewrite for style. Do not summarize. Do not explain your edits. Do not add or remove sentences.
        If a possible correction is uncertain, leave it unchanged.
        Return only the corrected text, with no heading, commentary, Markdown, or quotes around the response.

        OCR TEXT START
        $chunk
        OCR TEXT END
    """.trimIndent()

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
        val hasCorrectedText = OcrTextStore.hasCorrectedText()
        val source = if (hasCorrectedText) OcrTextStore.correctedFile().readText() else OcrTextStore.rawText()
        val title = binding.epubTitle.text.toString().trim().ifBlank { "Untitled" }
        val author = binding.epubAuthor.text.toString().trim().ifBlank { "Unknown author" }
        runCatching {
            contentResolver.openOutputStream(destination)?.use { EpubWriter.write(it, source, title, author) }
                ?: error("Could not create the EPUB file.")
        }.onSuccess {
            binding.statusText.text = "EPUB saved using ${if (hasCorrectedText) "corrected" else "raw"} text."
            Toast.makeText(this, "EPUB saved", Toast.LENGTH_LONG).show()
        }.onFailure { exception ->
            binding.statusText.text = "EPUB export failed: ${exception.message ?: "unknown error"}"
            Toast.makeText(this, "EPUB export failed", Toast.LENGTH_LONG).show()
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
        const val CORRECTION_CHUNK_CHAR_LIMIT = 2_500
        const val OLLAMA_CONNECT_TIMEOUT_MS = 15_000
        const val OLLAMA_READ_TIMEOUT_MS = 120_000
    }
}
