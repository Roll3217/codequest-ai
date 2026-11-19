package com.example.ui.settings

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.CodeQuestApp
import com.example.R
import com.example.databinding.ActivitySettingsBinding
import com.example.model.ThemeMode
import com.example.ui.auth.LoginActivity
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var isBindingState = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as CodeQuestApp
        app.sessionPreferences.applySavedTheme()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSettingsBack.setOnClickListener {
            finish()
        }

        bindCurrentPreferences(app)
        setupListeners(app)
    }

    private fun bindCurrentPreferences(app: CodeQuestApp) {
        isBindingState = true
        when (app.sessionPreferences.themeMode) {
            ThemeMode.DARK -> binding.rbSettingsThemeDark.isChecked = true
            ThemeMode.LIGHT -> binding.rbSettingsThemeLight.isChecked = true
            ThemeMode.SYSTEM -> binding.rbSettingsThemeSystem.isChecked = true
        }

        val email = app.sessionPreferences.loggedInUserEmail
        binding.tvSettingsAccountEmail.text =
            if (!email.isNullOrBlank()) "Signed in as: $email" else "Not logged in"

        val notifPrefs = app.sessionPreferences.getNotificationPreferences()
        binding.switchPrefAchievements.isChecked = notifPrefs.achievementUnlockedAlert
        binding.switchPrefDailyGoals.isChecked = notifPrefs.learningGoalsAlert
        binding.switchPrefStreak.isChecked = notifPrefs.streakReminder
        binding.switchPrefDailyChallenge.isChecked = notifPrefs.dailyChallengeAlert
        binding.switchPrefDailyReminder.isChecked = notifPrefs.dailyCodingReminder
        binding.switchPrefSmartHints.isChecked = notifPrefs.smartHints
        binding.switchPrefSoundEffects.isChecked = notifPrefs.soundEffects

        // Bind Supabase Cloud Status
        if (app.supabaseRepository.isConfigured()) {
            binding.tvSettingsSupabaseStatus.text = "● CHECKING..."
            binding.tvSettingsSupabaseStatus.setTextColor(getColor(R.color.cq_cyan_bright))
            binding.tvSettingsSupabaseEndpoint.text = "Host: ${app.supabaseRepository.getEndpointDisplay()}"
            binding.tvSettingsSupabaseDescription.text = "Connecting to Supabase PostgreSQL..."
            binding.btnSettingsSyncSupabase.isEnabled = true

            lifecycleScope.launch {
                val diag = app.supabaseRepository.pingDiagnostic()
                binding.tvSettingsSupabaseStatus.text = diag.statusLabel
                binding.tvSettingsSupabaseStatus.setTextColor(
                    getColor(
                        if (diag.tablesReady) R.color.cq_cyan_bright
                        else R.color.cq_text_secondary
                    )
                )
                binding.tvSettingsSupabaseDescription.text = diag.detailMessage
            }
        } else {
            binding.tvSettingsSupabaseStatus.text = "○ LOCAL MODE"
            binding.tvSettingsSupabaseStatus.setTextColor(getColor(R.color.cq_text_secondary))
            binding.tvSettingsSupabaseEndpoint.text = "Storage: On-Device Local Cache"
            binding.tvSettingsSupabaseDescription.text = "Your solved problems, streaks, and notes are safely stored on this device."
            binding.btnSettingsSyncSupabase.isEnabled = false
        }

        isBindingState = false
    }

    private fun setupListeners(app: CodeQuestApp) {
        binding.btnSettingsSyncSupabase.setOnClickListener {
            val userId = app.sessionPreferences.loggedInUserId
            if (userId <= 0L) {
                android.widget.Toast.makeText(this, "Please log in to sync with Supabase.", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnSettingsSyncSupabase.isEnabled = false
            binding.btnSettingsSyncSupabase.text = "Syncing..."
            lifecycleScope.launch {
                val result = app.supabaseRepository.syncUserData(userId)
                binding.btnSettingsSyncSupabase.isEnabled = true
                binding.btnSettingsSyncSupabase.text = "Sync Cloud Now"
                when (result) {
                    is com.example.data.remote.supabase.SupabaseSyncResult.Success -> {
                        android.widget.Toast.makeText(this@SettingsActivity, result.message, android.widget.Toast.LENGTH_LONG).show()
                    }
                    is com.example.data.remote.supabase.SupabaseSyncResult.Unconfigured -> {
                        android.widget.Toast.makeText(this@SettingsActivity, result.message, android.widget.Toast.LENGTH_LONG).show()
                    }
                    is com.example.data.remote.supabase.SupabaseSyncResult.Error -> {
                        android.widget.Toast.makeText(this@SettingsActivity, result.message, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        binding.btnSettingsSupabaseGuide.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Cloud Sync & Cross-Device Access")
                .setMessage(
                    "Your CodeQuest AI progress stays in sync automatically:\n\n" +
                    "• Profile & Identity: Full name, avatar, interests, and preferred language.\n" +
                    "• Coding Progress: XP, level, coding streak, solved problems, and topic mastery.\n" +
                    "• Study Workspace: Bookmarks, Mistakes Notebook notes, and Daily Goals.\n\n" +
                    "Sign in with the same registered Gmail address on any Android device to restore your progress, or tap 'Sync Cloud Now' anytime."
                )
                .setPositiveButton("Got It", null)
                .show()
        }

        binding.rgSettingsTheme.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingState) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.rb_settings_theme_dark -> ThemeMode.DARK
                R.id.rb_settings_theme_light -> ThemeMode.LIGHT
                else -> ThemeMode.SYSTEM
            }
            if (app.sessionPreferences.themeMode != mode) {
                app.sessionPreferences.themeMode = mode
            }
        }

        binding.btnSettingsLogout.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Log Out")
                .setMessage("Are you sure you want to log out of your CodeQuest AI account?")
                .setPositiveButton("Log Out") { _, _ ->
                    app.authRepository.logout()
                    val intent = Intent(this, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnSettingsDeleteAccount.setOnClickListener {
            val userId = app.sessionPreferences.loggedInUserId
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(com.example.R.string.dialog_delete_account_title))
                .setMessage(getString(com.example.R.string.dialog_delete_account_msg))
                .setPositiveButton(getString(com.example.R.string.action_confirm_delete)) { _, _ ->
                    lifecycleScope.launch {
                        val deleted = app.authRepository.deleteAccount(userId)
                        if (deleted) {
                            android.widget.Toast.makeText(this@SettingsActivity, "Account deleted successfully.", android.widget.Toast.LENGTH_LONG).show()
                            val intent = Intent(this@SettingsActivity, LoginActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                            startActivity(intent)
                            finish()
                        }
                    }
                }
                .setNegativeButton(getString(com.example.R.string.action_cancel), null)
                .show()
        }

        val switchListener = { _: Any, _: Boolean ->
            if (!isBindingState) {
                val updated = NotificationPreferencesState(
                    dailyCodingReminder = binding.switchPrefDailyReminder.isChecked,
                    streakReminder = binding.switchPrefStreak.isChecked,
                    dailyChallengeAlert = binding.switchPrefDailyChallenge.isChecked,
                    achievementUnlockedAlert = binding.switchPrefAchievements.isChecked,
                    learningGoalsAlert = binding.switchPrefDailyGoals.isChecked,
                    soundEffects = binding.switchPrefSoundEffects.isChecked,
                    smartHints = binding.switchPrefSmartHints.isChecked
                )
                app.sessionPreferences.saveNotificationPreferences(updated)
            }
        }

        binding.switchPrefAchievements.setOnCheckedChangeListener(switchListener)
        binding.switchPrefDailyGoals.setOnCheckedChangeListener(switchListener)
        binding.switchPrefStreak.setOnCheckedChangeListener(switchListener)
        binding.switchPrefDailyChallenge.setOnCheckedChangeListener(switchListener)
        binding.switchPrefDailyReminder.setOnCheckedChangeListener(switchListener)
        binding.switchPrefSmartHints.setOnCheckedChangeListener(switchListener)
        binding.switchPrefSoundEffects.setOnCheckedChangeListener(switchListener)
    }
}
