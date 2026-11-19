package com.example.ui.settings

data class NotificationPreferencesState(
    val dailyCodingReminder: Boolean = true,
    val streakReminder: Boolean = true,
    val dailyChallengeAlert: Boolean = true,
    val achievementUnlockedAlert: Boolean = true,
    val learningGoalsAlert: Boolean = true,
    val soundEffects: Boolean = true,
    val smartHints: Boolean = true
)
