package com.example.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.SessionPreferencesManager
import com.example.data.repository.LearningRepository
import com.example.model.AppNotificationEntity
import com.example.model.UserEntity
import kotlinx.coroutines.flow.Flow
import kotlin.math.abs
import kotlin.random.Random

/**
 * Service handling Android system notification drawer alerts and in-app notifications.
 * Provides goal proximity alerts, challenge reminders, achievement/level-up alerts,
 * new avatar alerts, and entertaining roasted messages for broken streaks and failed attempts.
 */
interface NotificationService {
    fun observeNotifications(userId: Long): Flow<List<AppNotificationEntity>>
    fun observeUnreadCount(userId: Long): Flow<Int>
    suspend fun markAsRead(notificationId: Long)
    suspend fun markAllAsRead(userId: Long)
    suspend fun clearRead(userId: Long)

    suspend fun triggerLoginNotifications(context: Context, user: UserEntity)
    suspend fun triggerRoastNotification(context: Context, userId: Long, roastType: RoastType, contextInfo: String = "")
    suspend fun triggerAvatarUnlockNotification(context: Context, userId: Long, avatarTitle: String)
    suspend fun postSystemNotification(context: Context, notifId: Int, title: String, message: String, isRoast: Boolean = false): Boolean
    suspend fun postNotificationEntity(context: Context, notification: AppNotificationEntity): Boolean
    suspend fun flushUnpostedNotificationsToTray(context: Context, userId: Long): Int
    suspend fun sendTestDeviceNotification(context: Context, userId: Long): Boolean
    fun areDeviceNotificationsPermitted(context: Context): Boolean
}

enum class RoastType {
    STREAK_LOST,
    FAILED_CODE_SUBMISSION,
    ZERO_GOALS_TODAY,
    IDLE_LEARNER
}

class RoomNotificationService(
    private val learningRepository: LearningRepository,
    private val sessionPreferences: SessionPreferencesManager? = null
) : NotificationService {

    companion object {
        const val LEGACY_CHANNEL_ID = "cq_engagement_channel"
        const val CHANNEL_ENGAGEMENT_ID = "cq_alerts_high_v2"
        const val CHANNEL_ENGAGEMENT_NAME = "CodeQuest Alerts & Milestones"
        const val CHANNEL_ENGAGEMENT_DESC = "Achievements, level-ups, daily goals, challenges, streak alerts, and AI roasts."

        val ROAST_STREAK_LOST = listOf(
            "Your streak died of neglect. Even your semicolon had more commitment than you.",
            "0-day streak? Ouch. Even HTML has more logic than skipping practice.",
            "Your coding streak flatlined. Time to revive your brain with PB-01!",
            "Did a NullPointerException delete your coding habit? Restart your streak today!"
        )

        val ROAST_FAILED_CODE = listOf(
            "That code didn't just fail test cases—it hurt the compiler's feelings.",
            "Infinite loop detected in your logic. Even the test runner needed a coffee break.",
            "Syntax looked fine, but your algorithm took the scenic route to wrong output.",
            "Off-by-one error strikes again! The oldest trick in the programmer playbook."
        )

        val ROAST_ZERO_GOALS = listOf(
            "Daily goals are waiting and you've done 0. The leaderboard is judging you silently.",
            "You were close to your goal, but closing the app won't submit the code!",
            "Your CPU is ready, but your coding energy is still in sleep mode. Solve 1 question now!",
            "Bug Hunter badge isn't going to unlock itself. Review a mistake or take a quiz!"
        )

        val GOAL_PROXIMITY_MESSAGES = listOf(
            "You're so close to today's goal! Just 1 more challenge to keep your streak glowing.",
            "Almost there! Finish your remaining daily goals to grab +50 XP and dominate the leaderboard.",
            "High momentum detected! Complete the remaining daily tasks to level up faster today."
        )

        val CHALLENGE_MESSAGES = listOf(
            "Today's Daily Challenge is live! Conquer it before midnight to score bonus XP.",
            "A new algorithm puzzle awaits your compiler! Can you solve it on the first attempt?",
            "Daily Challenge ready! Sharpen your Kotlin/Python skills and lock in today's streak."
        )

        val AVATAR_MESSAGES = listOf(
            "New Avatar Alert: Cyber Dragon is now available! Customize your coder identity in Profile.",
            "Synth Mech Avatar unlocked in your hangar! Gear up and show off your new look.",
            "Fresh avatar gear unlocked! Upgrade your profile aesthetic to match your rising XP."
        )

        fun notificationIdForKey(eventKey: String): Int {
            return (abs(eventKey.hashCode()) % 900_000) + 5000
        }
    }

    override fun observeNotifications(userId: Long): Flow<List<AppNotificationEntity>> {
        return learningRepository.observeNotifications(userId)
    }

    override fun observeUnreadCount(userId: Long): Flow<Int> {
        return learningRepository.observeUnreadNotificationCount(userId)
    }

    override suspend fun markAsRead(notificationId: Long) {
        learningRepository.markNotificationRead(notificationId)
    }

    override suspend fun markAllAsRead(userId: Long) {
        learningRepository.markAllNotificationsRead(userId)
    }

    override suspend fun clearRead(userId: Long) {
        learningRepository.clearReadNotifications(userId)
    }

    override fun areDeviceNotificationsPermitted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    override suspend fun triggerLoginNotifications(context: Context, user: UserEntity) {
        val userId = user.id
        if (userId <= 0L) return

        val today = LearningRepository.todayDateString()
        learningRepository.ensureInitializedForUser(userId, today)
        val prefs = sessionPreferences?.getNotificationPreferences()

        // 1. Check Goal Proximity & Streak status
        val goals = learningRepository.progressDaoDirect().getDailyGoals(userId, today)
        val completedCount = goals.count { it.isCompleted }
        val remainingCount = goals.size - completedCount

        if (remainingCount in 1..2 && completedCount > 0 && (prefs?.learningGoalsAlert ?: true)) {
            val title = "You're so close to your goal!"
            val msg = "Only $remainingCount goal(s) remaining today! Complete them now to protect your ${user.currentStreak}d streak and earn bonus XP."
            val key = "LOGIN_GOAL_PROXIMITY_${today}_${user.id}"
            insertAndNotify(
                context = context,
                userId = userId,
                eventKey = key,
                category = LearningRepository.NOTIF_CAT_DAILY_GOAL,
                title = title,
                message = msg,
                notifId = 1001,
                isRoast = false
            )
        } else if (completedCount == 0 && user.currentStreak > 0 && (prefs?.streakReminder ?: true)) {
            val roastMsg = ROAST_ZERO_GOALS.random()
            val title = "Don't break your ${user.currentStreak}d streak!"
            val key = "LOGIN_STREAK_URGENT_${today}_${user.id}"
            insertAndNotify(
                context = context,
                userId = userId,
                eventKey = key,
                category = LearningRepository.NOTIF_CAT_ROAST,
                title = title,
                message = "$roastMsg Complete at least 3 daily goals today!",
                notifId = 1002,
                isRoast = true
            )
        } else if (user.currentStreak == 0 && (prefs?.streakReminder ?: true)) {
            val roastMsg = ROAST_STREAK_LOST.random()
            val title = "Streak Status: 0 Days 🔥"
            val key = "LOGIN_STREAK_ZERO_${today}_${user.id}"
            insertAndNotify(
                context = context,
                userId = userId,
                eventKey = key,
                category = LearningRepository.NOTIF_CAT_ROAST,
                title = title,
                message = "$roastMsg Start fresh today with the Daily Challenge!",
                notifId = 1003,
                isRoast = true
            )
        }

        // 2. Daily Challenge Announcement
        if (prefs?.dailyChallengeAlert ?: true) {
            val challengeKey = "LOGIN_CHALLENGE_${today}_${user.id}"
            val challengeTitle = "Today's Coding Challenge is Waiting!"
            val challengeMsg = CHALLENGE_MESSAGES.random()
            insertAndNotify(
                context = context,
                userId = userId,
                eventKey = challengeKey,
                category = LearningRepository.NOTIF_CAT_DAILY_CHALLENGE,
                title = challengeTitle,
                message = challengeMsg,
                notifId = 1004,
                isRoast = false
            )
        }

        // 3. New Avatar Announcement
        val avatarKey = "LOGIN_AVATAR_SHOWCASE_${user.id}"
        val avatarTitle = "New Avatars Unlocked: Dragon & Mech"
        val avatarMsg = "Level up your look! Check out the newly added Cyber Dragon and Synth Mech avatars in your Profile."
        insertAndNotify(
            context = context,
            userId = userId,
            eventKey = avatarKey,
            category = LearningRepository.NOTIF_CAT_AVATAR,
            title = avatarTitle,
            message = avatarMsg,
            notifId = 1005,
            isRoast = false
        )
    }

    override suspend fun triggerRoastNotification(
        context: Context,
        userId: Long,
        roastType: RoastType,
        contextInfo: String
    ) {
        if (userId <= 0L) return
        val roastText = when (roastType) {
            RoastType.STREAK_LOST -> ROAST_STREAK_LOST.random()
            RoastType.FAILED_CODE_SUBMISSION -> ROAST_FAILED_CODE.random()
            RoastType.ZERO_GOALS_TODAY -> ROAST_ZERO_GOALS.random()
            RoastType.IDLE_LEARNER -> "Are you resting your brain or compiling your excuses? Solve a quick problem!"
        }

        val title = if (roastType == RoastType.FAILED_CODE_SUBMISSION) {
            "Compilation Roast 🔥"
        } else {
            "AI Mentor Reality Check 🔥"
        }

        val fullMessage = if (contextInfo.isNotBlank()) "$roastText ($contextInfo)" else roastText
        val eventKey = "ROAST_${roastType.name}_${System.currentTimeMillis()}"

        insertAndNotify(
            context = context,
            userId = userId,
            eventKey = eventKey,
            category = LearningRepository.NOTIF_CAT_ROAST,
            title = title,
            message = fullMessage,
            notifId = 2000 + Random.nextInt(500),
            isRoast = true
        )
    }

    override suspend fun triggerAvatarUnlockNotification(
        context: Context,
        userId: Long,
        avatarTitle: String
    ) {
        val title = "New Avatar Unlocked: $avatarTitle!"
        val msg = "Congratulations! You earned the $avatarTitle avatar. Tap to apply it to your coder profile."
        val key = "AVATAR_UNLOCKED_${avatarTitle.replace(" ", "_")}_${userId}"

        insertAndNotify(
            context = context,
            userId = userId,
            eventKey = key,
            category = LearningRepository.NOTIF_CAT_AVATAR,
            title = title,
            message = msg,
            notifId = 3001,
            isRoast = false
        )
    }

    override suspend fun sendTestDeviceNotification(context: Context, userId: Long): Boolean {
        val title = "CodeQuest AI Alerts Active!"
        val message = "Device notifications are working. Achievements, daily challenges, streak reminders, and coding roasts will appear right here."
        val key = "TEST_DEVICE_ALERT_${userId}_${System.currentTimeMillis()}"
        return if (userId > 0L) {
            val dao = learningRepository.progressDaoDirect()
            dao.insertNotification(
                AppNotificationEntity(
                    userId = userId,
                    uniqueEventKey = key,
                    category = LearningRepository.NOTIF_CAT_DAILY_CHALLENGE,
                    title = title,
                    message = message
                )
            )
            val posted = postSystemNotification(
                context = context,
                notifId = notificationIdForKey(key),
                title = title,
                message = message,
                isRoast = false
            )
            if (posted) {
                sessionPreferences?.markSystemNotificationPosted(userId, key)
            }
            posted
        } else {
            postSystemNotification(
                context = context,
                notifId = 4999,
                title = title,
                message = message,
                isRoast = false
            )
        }
    }

    override suspend fun postNotificationEntity(
        context: Context,
        notification: AppNotificationEntity
    ): Boolean {
        val notifId = notificationIdForKey(notification.uniqueEventKey)
        val posted = postSystemNotification(
            context = context,
            notifId = notifId,
            title = notification.title,
            message = notification.message,
            isRoast = notification.category == LearningRepository.NOTIF_CAT_ROAST
        )
        if (posted) {
            sessionPreferences?.markSystemNotificationPosted(notification.userId, notification.uniqueEventKey)
        }
        return posted
    }

    override suspend fun flushUnpostedNotificationsToTray(context: Context, userId: Long): Int {
        if (userId <= 0L || !areDeviceNotificationsPermitted(context)) return 0
        val prefs = sessionPreferences
        val unread = learningRepository.getNotifications(userId)
            .filter { !it.isRead }
            .filter { notif -> prefs == null || !prefs.isSystemNotificationPosted(userId, notif.uniqueEventKey) }

        if (unread.isEmpty()) return 0

        var postedCount = 0
        // Post up to 4 most recent unposted alerts cleanly to the device tray
        unread.take(4).forEachIndexed { idx, notif ->
            val stableId = when {
                notif.uniqueEventKey.startsWith("LOGIN_GOAL_PROXIMITY_") -> 1001
                notif.uniqueEventKey.startsWith("LOGIN_STREAK_URGENT_") -> 1002
                notif.uniqueEventKey.startsWith("LOGIN_STREAK_ZERO_") -> 1003
                notif.uniqueEventKey.startsWith("LOGIN_CHALLENGE_") -> 1004
                notif.uniqueEventKey.startsWith("LOGIN_AVATAR_SHOWCASE_") -> 1005
                else -> notificationIdForKey(notif.uniqueEventKey) + idx
            }
            val posted = postSystemNotification(
                context = context,
                notifId = stableId,
                title = notif.title,
                message = notif.message,
                isRoast = notif.category == LearningRepository.NOTIF_CAT_ROAST
            )
            if (posted) {
                postedCount++
            }
        }

        // Mark all unposted unread entries so older backlog isn't repeatedly flushed
        if (postedCount > 0 && prefs != null) {
            unread.forEach { prefs.markSystemNotificationPosted(userId, it.uniqueEventKey) }
        }
        return postedCount
    }

    private suspend fun insertAndNotify(
        context: Context,
        userId: Long,
        eventKey: String,
        category: String,
        title: String,
        message: String,
        notifId: Int,
        isRoast: Boolean
    ) {
        val dao = learningRepository.progressDaoDirect()
        val inserted = dao.insertNotification(
            AppNotificationEntity(
                userId = userId,
                uniqueEventKey = eventKey,
                category = category,
                title = title,
                message = message
            )
        )

        if (inserted > 0L) {
            val posted = postSystemNotification(context, notifId, title, message, isRoast)
            if (posted) {
                sessionPreferences?.markSystemNotificationPosted(userId, eventKey)
            }
        }
    }

    override suspend fun postSystemNotification(
        context: Context,
        notifId: Int,
        title: String,
        message: String,
        isRoast: Boolean
    ): Boolean {
        if (!areDeviceNotificationsPermitted(context)) {
            return false
        }
        return try {
            ensureNotificationChannel(context)
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_NOTIFICATIONS, true)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                notifId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val soundEnabled = sessionPreferences?.getNotificationPreferences()?.soundEffects ?: true
            val builder = NotificationCompat.Builder(context, CHANNEL_ENGAGEMENT_ID)
                .setSmallIcon(R.drawable.ic_stat_codequest)
                .setColor(ContextCompat.getColor(context, R.color.cq_cyan_bright))
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            if (soundEnabled) {
                builder.setDefaults(NotificationCompat.DEFAULT_ALL)
            } else {
                builder.setSilent(true)
            }

            notificationManager.notify(notifId, builder.build())
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            try {
                if (manager.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
                    manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
                }
            } catch (_: Exception) {}

            if (manager.getNotificationChannel(CHANNEL_ENGAGEMENT_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ENGAGEMENT_ID,
                    CHANNEL_ENGAGEMENT_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = CHANNEL_ENGAGEMENT_DESC
                    enableLights(true)
                    enableVibration(true)
                    setShowBadge(true)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}
