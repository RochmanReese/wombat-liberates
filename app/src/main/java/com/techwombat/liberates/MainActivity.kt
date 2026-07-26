package com.techwombat.liberates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
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

    private val pageUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == KindleTextExtractorService.ACTION_PAGE_CAPTURED) {
                val pageCount = intent.getIntExtra(KindleTextExtractorService.EXTRA_PAGE_COUNT, 0)
                val lineCount = intent.getIntExtra(KindleTextExtractorService.EXTRA_LAST_LINE_COUNT, 0)
                val lastPkg = intent.getStringExtra(KindleTextExtractorService.EXTRA_LAST_PACKAGE) ?: ""
                updateUI(pageCount, lineCount, lastPkg)
            }
        }
    }

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
            text = "Last App: ${KindleTextExtractorService.lastDetectedPackage}"
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
                sendBroadcast(Intent(KindleTextExtractorService.ACTION_START_CAPTURE))
                statusTv.text = "Capture Status: RUNNING"
                statusTv.setTextColor(Color.parseColor("#2E7D32"))
            }
        }
        layout.addView(startBtn)

        val stopBtn = Button(this).apply {
            text = "⏹ STOP CAPTURE"
            setBackgroundColor(Color.parseColor("#C62828"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setOnClickListener {
                sendBroadcast(Intent(KindleTextExtractorService.ACTION_STOP_CAPTURE))
                statusTv.text = "Capture Status: STOPPED"
                statusTv.setTextColor(Color.RED)
            }
        }
        layout.addView(stopBtn)

        val clearBtn = Button(this).apply {
            text = "🗑 CLEAR BUFFER"
            textSize = 16f
            setOnClickListener {
                sendBroadcast(Intent(KindleTextExtractorService.ACTION_CLEAR_BUFFER))
                updateUI(0, 0, "None")
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
        val filter = IntentFilter(KindleTextExtractorService.ACTION_PAGE_CAPTURED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pageUpdateReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(pageUpdateReceiver, filter)
        }

        // Sync initial state
        val count = KindleTextExtractorService.capturedPages.size
        val isRunning = KindleTextExtractorService.isCapturing
        statusTv.text = if (isRunning) "Capture Status: RUNNING" else "Capture Status: STOPPED"
        statusTv.setTextColor(if (isRunning) Color.parseColor("#2E7D32") else Color.RED)
        pageCountTv.text = "Pages Captured: $count"
        lastPkgTv.text = "Last App: ${KindleTextExtractorService.lastDetectedPackage}"
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(pageUpdateReceiver)
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun updateUI(pageCount: Int, lineCount: Int, lastPkg: String) {
        runOnUiThread {
            pageCountTv.text = "Pages Captured: $pageCount (Last page: $lineCount lines)"
            if (lastPkg.isNotEmpty()) {
                lastPkgTv.text = "Last App: $lastPkg"
            }
        }
    }
}
