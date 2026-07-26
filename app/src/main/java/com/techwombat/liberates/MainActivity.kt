package com.techwombat.liberates

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusTv: TextView
    private lateinit var pageCountTv: TextView
    private lateinit var autoSwipeTv: TextView
    private lateinit var lastPkgTv: TextView
    private lateinit var etBookTitle: EditText
    private lateinit var etBookAuthor: EditText
    private lateinit var etServerUrl: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTv = findViewById(R.id.statusTv)
        pageCountTv = findViewById(R.id.pageCountTv)
        autoSwipeTv = findViewById(R.id.autoSwipeTv)
        lastPkgTv = findViewById(R.id.lastPkgTv)
        etBookTitle = findViewById(R.id.etBookTitle)
        etBookAuthor = findViewById(R.id.etBookAuthor)
        etServerUrl = findViewById(R.id.etServerUrl)

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            KindleTextExtractorService.setCapturing(this, true)
            syncStateUI()
        }

        findViewById<Button>(R.id.btnAutoSwipe).setOnClickListener {
            val current = KindleTextExtractorService.isAutoSwipe(this)
            KindleTextExtractorService.setAutoSwipe(this, !current)
            syncStateUI()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            KindleTextExtractorService.setCapturing(this, false)
            syncStateUI()
        }

        findViewById<Button>(R.id.btnExportEpub).setOnClickListener {
            exportEpub(false)
        }

        findViewById<Button>(R.id.btnUploadServer).setOnClickListener {
            uploadToServer()
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            KindleTextExtractorService.clearPages(this)
            syncStateUI()
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun exportEpub(silent: Boolean = false): File? {
        val capturedPages = KindleTextExtractorService.getCapturedPages(this)
        if (capturedPages.isEmpty()) {
            if (!silent) Toast.makeText(this, "No pages captured to export!", Toast.LENGTH_SHORT).show()
            return null
        }

        val rawTitle = etBookTitle.text.toString().trim()
        val title = if (rawTitle.isNotEmpty()) rawTitle else "Liberated Book"

        val rawAuthor = etBookAuthor.text.toString().trim()
        val author = if (rawAuthor.isNotEmpty()) rawAuthor else "Wombat-Liberates"

        val pagesLines = capturedPages.map { it.textLines }
        val stitchedParagraphs = TextCleaner.stitchPageText(pagesLines)
        val chapters = ChapterSegmenter.segmentIntoChapters(stitchedParagraphs)

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }

        val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val outputFile = File(downloadsDir, "$sanitizedTitle.epub")

        return try {
            EpubPackager.createEpub(title, author, chapters, outputFile)
            if (!silent) Toast.makeText(this, "EPUB Exported to Downloads:\n${outputFile.name}", Toast.LENGTH_LONG).show()
            syncStateUI()
            outputFile
        } catch (e: Exception) {
            if (!silent) Toast.makeText(this, "Export Failed: ${e.message}", Toast.LENGTH_LONG).show()
            null
        }
    }

    private fun uploadToServer() {
        val serverUrl = etServerUrl.text.toString().trim()
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "Please enter your Server URL (e.g. 192.168.1.100:8000)", Toast.LENGTH_SHORT).show()
            return
        }

        val epubFile = exportEpub(silent = true)
        if (epubFile == null || !epubFile.exists()) {
            Toast.makeText(this, "No captured pages available to upload!", Toast.LENGTH_SHORT).show()
            return
        }

        val rawTitle = etBookTitle.text.toString().trim()
        val title = if (rawTitle.isNotEmpty()) rawTitle else "Liberated Book"

        val rawAuthor = etBookAuthor.text.toString().trim()
        val author = if (rawAuthor.isNotEmpty()) rawAuthor else "Wombat-Liberates"

        Toast.makeText(this, "Uploading ${epubFile.name} to $serverUrl...", Toast.LENGTH_SHORT).show()

        ServerUploader.uploadEpub(serverUrl, epubFile, title, author) { success, msg ->
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        syncStateUI()
    }

    private fun syncStateUI() {
        val prefs = KindleTextExtractorService.getPrefs(this)
        val isRunning = prefs.getBoolean(KindleTextExtractorService.PREF_IS_CAPTURING, false)
        val isAutoSwipe = prefs.getBoolean(KindleTextExtractorService.PREF_AUTO_SWIPE, false)
        val pageCount = prefs.getInt(KindleTextExtractorService.PREF_PAGE_COUNT, 0)
        val lineCount = prefs.getInt(KindleTextExtractorService.PREF_LAST_LINE_COUNT, 0)
        val lastPkg = prefs.getString(KindleTextExtractorService.PREF_LAST_PACKAGE, "None") ?: "None"

        runOnUiThread {
            statusTv.text = if (isRunning) "Capture Status: RUNNING" else "Capture Status: STOPPED"
            statusTv.setTextColor(if (isRunning) Color.parseColor("#2E7D32") else Color.RED)
            
            autoSwipeTv.text = if (isAutoSwipe) "Auto-Swipe: ON (1.5s-2.1s delay)" else "Auto-Swipe: OFF"
            autoSwipeTv.setTextColor(if (isAutoSwipe) Color.parseColor("#1565C0") else Color.GRAY)

            pageCountTv.text = "Pages Captured: $pageCount (Last page: $lineCount lines)"
            lastPkgTv.text = "Last App Detected: $lastPkg"
        }
    }
}
