package com.techwombat.liberates

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.techwombat.liberates.databinding.ActivityHelpBinding

class HelpActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.closeHelpButton.setOnClickListener { finish() }
    }
}
