package com.techwombat.liberates

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val titleView = TextView(this).apply {
            text = "Wombat-Liberates"
            textSize = 24f
            setPadding(0, 0, 0, 24)
        }
        layout.addView(titleView)

        val descView = TextView(this).apply {
            text = "Kindle Accessibility Text Extractor.\n\nTap below to enable Accessibility Permission in Settings."
            textSize = 16f
            setPadding(0, 0, 0, 48)
        }
        layout.addView(descView)

        val settingsBtn = Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        layout.addView(settingsBtn)

        setContentView(layout)
    }
}
