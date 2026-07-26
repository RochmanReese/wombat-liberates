package com.techwombat.liberates

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.techwombat.liberates.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
        val saveRawText = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) writeTextTo(uri, OcrTextStore.rawFile(), "Raw OCR text saved")
        }
        val saveCorrectedText = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) writeTextTo(uri, OcrTextStore.correctedFile(), "Corrected OCR text saved")
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
                binding.statusText.text = "Enter a page count between 1 and 1,000."
            } else {
                startBatch(pageCount)
            }
        }
        binding.stopBatchButton.setOnClickListener {
            KindleAccessibilityProbeService.stopBatch()
            renderState()
        }
        binding.correctOnDeviceButton.setOnClickListener { startOnDeviceCorrection() }
        binding.saveRawTextButton.setOnClickListener { saveRawText.launch(exportFileName("kindle-ocr")) }
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
        binding.armTreeSnapshotButton.setOnClickListener {
            if (KindleAccessibilityProbeService.armNextTreeSnapshot()) {
                binding.statusText.text = "Tree snapshot armed; switch to Kindle."
            } else {
                binding.statusText.text = "Start the probe and enable accessibility first."
            }
        }
        binding.armOcrButton.setOnClickListener {
            if (KindleAccessibilityProbeService.armOnePageOcrCapture()) {
                binding.statusText.text = "One-page OCR armed; switch to Kindle."
            } else {
                binding.statusText.text = "Start the probe and enable accessibility first."
            }
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
        if (KindleAccessibilityProbeService.startBatch(pageCount)) {
            binding.statusText.text = "Book capture armed for $pageCount pages; open Kindle at the starting page."
        } else {
            binding.statusText.text = "Start the probe and enable accessibility first."
        }
    }

    private fun startOnDeviceCorrection() {
        if (correctionJob?.isActive == true) return
        val rawText = OcrTextStore.rawText()
        if (rawText.isBlank()) {
            binding.statusText.text = "Capture raw OCR text before correcting it."
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
                    runCorrection(rawText)
                }
                FeatureStatus.AVAILABLE -> runCorrection(rawText)
                else -> binding.statusText.text = "On-device correction is not ready yet."
            }
        }
    }

    private suspend fun runCorrection(rawText: String) {
        val chunks = chunkForCorrection(rawText)
        OcrTextStore.beginCorrectedText()
        binding.correctOnDeviceButton.isEnabled = false
        try {
            chunks.forEachIndexed { index, chunk ->
                binding.statusText.text = "Correcting chunk ${index + 1} of ${chunks.size}; keep this app open."
                val corrected = correctionModel.generateContent(correctionPrompt(chunk)).candidates.firstOrNull()?.text.orEmpty()
                if (corrected.isBlank()) error("The on-device model returned no corrected text.")
                OcrTextStore.appendCorrectedChunk(corrected)
            }
            binding.statusText.text = "On-device correction complete: ${chunks.size} chunks saved separately."
        } catch (error: Exception) {
            binding.statusText.text = "Correction stopped: ${error.message ?: error.javaClass.simpleName}. Raw OCR is unchanged."
        } finally {
            binding.correctOnDeviceButton.isEnabled = true
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
        Correct only obvious OCR character, capitalization, punctuation, and word-break errors.
        Preserve every sentence, paragraph break, dialogue mark, chapter heading, proper name, and the author's wording.
        Do not summarize, rewrite, explain, add, or remove content. Return only the corrected text.

        OCR TEXT:
        $chunk
    """.trimIndent()

    private fun renderState() {
        binding.statusText.text = if (ProbeLog.isActive) {
            "Probe: running. ${KindleAccessibilityProbeService.batchStatus()}"
        } else {
            "Probe: stopped"
        }
        binding.startButton.isEnabled = !ProbeLog.isActive
        binding.stopButton.isEnabled = ProbeLog.isActive
        binding.logText.text = ProbeLog.snapshot()
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
            .setTitle("Enable the accessibility service")
            .setMessage("Android must enable the probe service before it can receive Kindle events. Starting the probe does not enable it automatically.")
            .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("Not now", null)
            .show()
    }

    private companion object {
        const val CORRECTION_CHUNK_CHAR_LIMIT = 2_500
    }
}
