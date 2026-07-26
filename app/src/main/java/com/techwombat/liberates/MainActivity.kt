package com.techwombat.liberates

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusTv: TextView
    private lateinit var pageCountTv: TextView
    private lateinit var autoSwipeTv: TextView
    private lateinit var lastPkgTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusTv = findViewById(R.id.statusTv)
        pageCountTv = findViewById(R.id.pageCountTv)
        autoSwipeTv = findViewById(R.id.autoSwipeTv)
        lastPkgTv = findViewById(R.id.lastPkgTv)

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

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            KindleTextExtractorService.clearPages(this)
            syncStateUI()
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
            
            autoSwipeTv.text = if (isAutoSwipe) "Auto-Swipe: ON (1.5s delay)" else "Auto-Swipe: OFF"
            autoSwipeTv.setTextColor(if (isAutoSwipe) Color.parseColor("#1565C0") else Color.GRAY)

            pageCountTv.text = "Pages Captured: $pageCount (Last page: $lineCount lines)"
            lastPkgTv.text = "Last App Detected: $lastPkg"
        }
    }
}
