package com.techwombat.liberates

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.techwombat.liberates.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val logListener: (String) -> Unit = { log ->
        runOnUiThread { binding.logText.text = log }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PersistentProbeLog.initialize(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val saveLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) writeLogTo(uri)
        }

        binding.startButton.setOnClickListener {
            ProbeLog.start()
            renderState()
            if (!isAccessibilityServiceEnabled()) showAccessibilitySetupDialog()
        }
        binding.stopButton.setOnClickListener {
            ProbeLog.stop()
            renderState()
        }
        binding.saveLogButton.setOnClickListener {
            saveLog.launch(exportFileName())
        }
        binding.clearLogButton.setOnClickListener {
            ProbeLog.clear()
            Toast.makeText(this, "Log cleared from this phone", Toast.LENGTH_SHORT).show()
        }
        binding.armTreeSnapshotButton.setOnClickListener {
            val message = if (KindleAccessibilityProbeService.armNextTreeSnapshot()) {
                "Snapshot armed. Switch to Kindle; its next event will be logged once."
            } else {
                "Start the probe and enable its accessibility service before arming a snapshot."
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        binding.armOcrButton.setOnClickListener {
            val message = if (KindleAccessibilityProbeService.armOnePageOcrCapture()) {
                "OCR capture armed. Switch to Kindle; its next event will be captured once."
            } else {
                "Start the probe and enable its accessibility service before arming OCR."
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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

    private fun renderState() {
        binding.statusText.text = if (ProbeLog.isActive) "Probe: running (Kindle events only)" else "Probe: stopped"
        binding.startButton.isEnabled = !ProbeLog.isActive
        binding.stopButton.isEnabled = ProbeLog.isActive
        binding.logText.text = ProbeLog.snapshot()
    }

    private fun exportFileName(): String {
        val entered = binding.exportFileName.text.toString().trim().ifBlank { "kindle-ocr" }
        val safeName = entered.map { character ->
            if (character.isLetterOrDigit() || character == '.' || character == '_' || character == '-') character else '_'
        }.joinToString("")
        return if (safeName.endsWith(".txt", ignoreCase = true)) safeName else "$safeName.txt"
    }

    private fun writeLogTo(destination: Uri) {
        contentResolver.openOutputStream(destination)?.use { output ->
            PersistentProbeLog.file().inputStream().use { input -> input.copyTo(output) }
        } ?: return
        Toast.makeText(this, "Log file saved", Toast.LENGTH_SHORT).show()
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
}
