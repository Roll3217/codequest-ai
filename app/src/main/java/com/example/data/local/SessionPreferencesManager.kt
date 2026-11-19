package com.example.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.example.model.ThemeMode
import com.example.ui.settings.NotificationPreferencesState

class SessionPreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()

    var hasRequestedNotificationPermission: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, value).apply()

    var loggedInUserId: Long
        get() = prefs.getLong(KEY_LOGGED_IN_USER_ID, -1L)
        set(value) = prefs.edit().putLong(KEY_LOGGED_IN_USER_ID, value).apply()

    var loggedInUserEmail: String?
        get() = prefs.getString(KEY_LOGGED_IN_USER_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_LOGGED_IN_USER_EMAIL, value).apply()

    var themeMode: ThemeMode
        get() = ThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, ThemeMode.DARK.storageKey))
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value.storageKey).apply()
            applyTheme(value)
        }

    var preferredLanguage: String
        get() = prefs.getString(KEY_PREFERRED_LANGUAGE, "Kotlin") ?: "Kotlin"
        set(value) = prefs.edit().putString(KEY_PREFERRED_LANGUAGE, value).apply()

    fun getNotificationPreferences(): NotificationPreferencesState {
        return NotificationPreferencesState(
            dailyCodingReminder = prefs.getBoolean(KEY_PREF_DAILY_REMINDER, true),
            streakReminder = prefs.getBoolean(KEY_PREF_STREAK_REMINDER, true),
            dailyChallengeAlert = prefs.getBoolean(KEY_PREF_DAILY_CHALLENGE_ALERT, true),
            achievementUnlockedAlert = prefs.getBoolean(KEY_PREF_ACHIEVEMENT_ALERT, true),
            learningGoalsAlert = prefs.getBoolean(KEY_PREF_LEARNING_GOALS_ALERT, true),
            soundEffects = prefs.getBoolean(KEY_PREF_SOUND_EFFECTS, true),
            smartHints = prefs.getBoolean(KEY_PREF_SMART_HINTS, true)
        )
    }

    fun saveNotificationPreferences(state: NotificationPreferencesState) {
        prefs.edit()
            .putBoolean(KEY_PREF_DAILY_REMINDER, state.dailyCodingReminder)
            .putBoolean(KEY_PREF_STREAK_REMINDER, state.streakReminder)
            .putBoolean(KEY_PREF_DAILY_CHALLENGE_ALERT, state.dailyChallengeAlert)
            .putBoolean(KEY_PREF_ACHIEVEMENT_ALERT, state.achievementUnlockedAlert)
            .putBoolean(KEY_PREF_LEARNING_GOALS_ALERT, state.learningGoalsAlert)
            .putBoolean(KEY_PREF_SOUND_EFFECTS, state.soundEffects)
            .putBoolean(KEY_PREF_SMART_HINTS, state.smartHints)
            .apply()
    }

    var supabaseAccessToken: String?
        get() = prefs.getString(KEY_SUPABASE_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_SUPABASE_ACCESS_TOKEN, value).apply()

    var supabaseRefreshToken: String?
        get() = prefs.getString(KEY_SUPABASE_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_SUPABASE_REFRESH_TOKEN, value).apply()

    fun isUserLoggedIn(): Boolean = loggedInUserId > 0L && !loggedInUserEmail.isNullOrBlank()

    fun saveSession(userId: Long, email: String, supabaseToken: String? = null) {
        val editor = prefs.edit()
            .putLong(KEY_LOGGED_IN_USER_ID, userId)
            .putString(KEY_LOGGED_IN_USER_EMAIL, email)
        if (!supabaseToken.isNullOrBlank()) {
            editor.putString(KEY_SUPABASE_ACCESS_TOKEN, supabaseToken)
        }
        editor.apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_LOGGED_IN_USER_ID)
            .remove(KEY_LOGGED_IN_USER_EMAIL)
            .remove(KEY_SUPABASE_ACCESS_TOKEN)
            .remove(KEY_SUPABASE_REFRESH_TOKEN)
            .apply()
    }

    fun isSystemNotificationPosted(userId: Long, eventKey: String): Boolean {
        if (userId <= 0L || eventKey.isBlank()) return false
        val existing = prefs.getStringSet("${KEY_POSTED_TRAY_PREFIX}$userId", emptySet()) ?: emptySet()
        return eventKey in existing
    }

    fun markSystemNotificationPosted(userId: Long, eventKey: String) {
        if (userId <= 0L || eventKey.isBlank()) return
        val key = "${KEY_POSTED_TRAY_PREFIX}$userId"
        val current = (prefs.getStringSet(key, emptySet()) ?: emptySet()).toMutableList()
        if (eventKey !in current) {
            current.add(eventKey)
            val trimmed = if (current.size > 100) current.takeLast(100) else current
            prefs.edit().putStringSet(key, trimmed.toSet()).apply()
        }
    }

    fun applySavedTheme() {
        applyTheme(themeMode)
    }

    fun cycleThemeMode(): ThemeMode {
        val next = when (themeMode) {
            ThemeMode.DARK -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.SYSTEM
            ThemeMode.SYSTEM -> ThemeMode.DARK
        }
        themeMode = next
        return next
    }

    companion object {
        private const val PREFS_NAME = "codequest_ai_session_prefs"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
        private const val KEY_LOGGED_IN_USER_ID = "logged_in_user_id"
        private const val KEY_LOGGED_IN_USER_EMAIL = "logged_in_user_email"
        private const val KEY_SUPABASE_ACCESS_TOKEN = "supabase_access_token"
        private const val KEY_SUPABASE_REFRESH_TOKEN = "supabase_refresh_token"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_PREFERRED_LANGUAGE = "preferred_coding_language"

        private const val KEY_PREF_DAILY_REMINDER = "pref_daily_coding_reminder"
        private const val KEY_PREF_STREAK_REMINDER = "pref_streak_reminder"
        private const val KEY_PREF_DAILY_CHALLENGE_ALERT = "pref_daily_challenge_alert"
        private const val KEY_PREF_ACHIEVEMENT_ALERT = "pref_achievement_unlocked_alert"
        private const val KEY_PREF_LEARNING_GOALS_ALERT = "pref_learning_goals_alert"
        private const val KEY_PREF_SOUND_EFFECTS = "pref_sound_effects"
        private const val KEY_PREF_SMART_HINTS = "pref_smart_hints"
        private const val KEY_POSTED_TRAY_PREFIX = "posted_tray_keys_"

        fun applyTheme(mode: ThemeMode) {
            val nightMode = when (mode) {
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
                AppCompatDelegate.setDefaultNightMode(nightMode)
            }
        }
    }
}
