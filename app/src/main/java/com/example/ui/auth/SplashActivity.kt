package com.example.ui.auth

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.CodeQuestApp
import com.example.MainActivity
import com.example.databinding.ActivitySplashBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as CodeQuestApp
        app.sessionPreferences.applySavedTheme()
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Subtle entrance animation for the logo card
        binding.cardLogoContainer.alpha = 0f
        binding.cardLogoContainer.scaleX = 0.85f
        binding.cardLogoContainer.scaleY = 0.85f
        binding.cardLogoContainer.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(450L)
            .start()

        lifecycleScope.launch {
            delay(750L)

            val validUser = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (app.sessionPreferences.isUserLoggedIn()) {
                    app.authRepository.getLoggedInUser()
                } else {
                    null
                }
            }

            if (validUser != null) {
                // Verified logged-in user exists -> go to MainActivity
                startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            } else {
                // Never proceed past Login/Signup without an authenticated user record
                app.sessionPreferences.clearSession()
                startActivity(Intent(this@SplashActivity, LoginActivity::class.java))
            }
            finish()
        }
    }
}
