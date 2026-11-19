package com.example.ui.auth

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.CodeQuestApp
import com.example.databinding.ActivityWelcomeBinding

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as CodeQuestApp
        app.sessionPreferences.applySavedTheme()
        super.onCreate(savedInstanceState)
        
        val targetActivity = if (app.sessionPreferences.isUserLoggedIn()) {
            com.example.MainActivity::class.java
        } else {
            LoginActivity::class.java
        }
        startActivity(Intent(this, targetActivity).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun updateThemeButtonLabel(app: CodeQuestApp) {
        binding.btnWelcomeTheme.text = "Theme: ${app.sessionPreferences.themeMode.storageKey}"
    }
}
