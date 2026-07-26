package com.techwombat.liberates

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusTv: TextView
    private lateinit var pageCountTv: TextView
    private lateinit var lastPkgTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 60)
        }

        val titleView = TextView(this).apply {
            text = "Wombat-Liberates"
            textSize = 28f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(titleView)

        val descView = TextView(this).apply {
            text = "Universal Accessibility Text Extractor"
            textSize = 16f
            setPadding(0, 0, 0, 36)
        }
        layout.addView(descView)

        // Status Indicators
        statusTv = TextView(this).apply {
            text = "Capture Status: DISABLED"
            textSize = 18f
            setTextColor(Color.RED)
            setPadding(0, 0, 0, 12)
        }
        layout.addView(statusTv)

        pageCountTv = TextView(this).apply {
            text = "Pages Captured: 0"
            textSize = 18f
            setPadding(0, 0, 0, 12)
        }
        layout.addView(pageCountTv)

        lastPkgTv = TextView(this).apply {
            text = "Last App: None"
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, 36)
        }
        layout.addView(lastPkgTv)

        // Buttons
        val startBtn = Button(this).apply {
            text = "▶ START CAPTURE"
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setOnClickListener {
                KindleTextExtractorService.setCapturing(this@MainActivity, true)
                syncStateUI()
            }
        }
        layout.addView(startBtn)

        val stopBtn = Button(this).apply {
            text = "⏹ STOP CAPTURE"
            setBackgroundColor(Color.parseColor("#C62828"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setOnClickListener {
                KindleTextExtractorService.setCapturing(this@MainActivity, false)
                syncStateUI()
            }
        }
        layout.addView(stopBtn)

        val clearBtn = Button(this).apply {
            text = "🗑 CLEAR BUFFER"
            textSize = 16f
            setOnClickListener {
                KindleTextExtractorService.clearPages(this@MainActivity)
                syncStateUI()
            }
        }
        layout.addView(clearBtn)

        val settingsBtn = Button(this).apply {
            text = "⚙ Open Accessibility Settings"
            textSize = 14f
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        layout.addView(settingsBtn)

        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        syncStateUI()
    }

    private fun syncStateUI() {
        val prefs = KindleTextExtractorService.getPrefs(this)
        val isRunning = prefs.getBoolean(KindleTextExtractorService.PREF_IS_CAPTURING, false)
        val pageCount = prefs.getInt(KindleTextExtractorService.PREF_PAGE_COUNT, 0)
        val lineCount = prefs.getInt(KindleTextExtractorService.PREF_LAST_LINE_COUNT, 0)
        val lastPkg = prefs.getString(KindleTextExtractorService.PREF_LAST_PACKAGE, "None") ?: "None"

        runOnUiThread {
            statusTv.text = if (isRunning) "Capture Status: RUNNING" else "Capture Status: STOPPED"
            statusTv.setTextColor(if (isRunning) Color.parseColor("#2E7D32") else Color.RED)
            pageCountTv.text = "Pages Captured: $pageCount (Last page: $lineCount lines)"
            lastPkgTv.text = "Last App Detected: $lastPkg"
        }
    }
}
