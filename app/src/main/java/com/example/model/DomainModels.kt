package com.example.model

import androidx.annotation.DrawableRes
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ThemeMode(val storageKey: String) {
    DARK("DARK"),
    LIGHT("LIGHT"),
    SYSTEM("SYSTEM");

    companion object {
        fun fromKey(key: String?): ThemeMode {
            return entries.firstOrNull { it.storageKey == key } ?: DARK
        }
    }
}

data class AvatarOption(
    val id: String,
    val title: String,
    @DrawableRes val drawableResId: Int
)

@Entity(
    tableName = "users",
    indices = [Index(value = ["email"], unique = true)]
)
data class UserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fullName: String,
    val email: String,
    val passwordHash: String,
    val avatarKey: String,
    val isCustomPhoto: Boolean = false,
    val interests: String = "Arrays, Dynamic Programming, Graphs",
    val preferredLanguage: String = "Kotlin",
    val xp: Int = 0,
    val level: Int = 1,
    val coins: Int = 50,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val lastQualifyingDate: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "questions")
data class QuestionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val topic: String,
    val subtopic: String,
    val difficulty: String, // Easy, Medium, Hard
    val examplesJson: String,
    val constraintsText: String,
    val expectedInput: String,
    val expectedOutput: String,
    val tagsCsv: String,
    val testCasesJson: String,
    val hintsJson: String,
    val approachText: String,
    val complexityText: String,
    val starterCodeJson: String,
    val solutionsJson: String
)

data class QuestionWithStatus(
    val question: QuestionEntity,
    val isSolved: Boolean,
    val isBookmarked: Boolean,
    val isInMistakes: Boolean
)

data class DailyChallengeStatus(
    val dateString: String,
    val question: QuestionEntity,
    val xpReward: Int = 50,
    val isCompleted: Boolean
)

@Entity(tableName = "attempts")
data class AttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val questionId: String,
    val questionTitle: String = "",
    val topic: String = "",
    val difficulty: String = "",
    val language: String,
    val submittedCode: String,
    val passed: Boolean,
    val statusMessage: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "solved_questions",
    indices = [Index(value = ["userId", "questionId"], unique = true)]
)
data class SolvedQuestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val questionId: String,
    val topic: String,
    val difficulty: String,
    val solvedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "topic_progress",
    indices = [Index(value = ["userId", "topicName"], unique = true)]
)
data class TopicProgressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val topicIndex: Int,
    val topicName: String,
    val description: String,
    val solvedCount: Int = 0,
    val maxCount: Int = 10
) {
    val percentage: Int
        get() = if (maxCount <= 0) 0 else ((solvedCount.coerceIn(0, maxCount) * 100) / maxCount)
}

@Entity(
    tableName = "daily_goals",
    indices = [Index(value = ["userId", "dateString", "goalKey"], unique = true)]
)
data class DailyGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val dateString: String,
    val goalKey: String,
    val title: String,
    val xpReward: Int,
    val isCompleted: Boolean = false,
    val completedAt: Long? = null
)

@Entity(
    tableName = "mistakes",
    indices = [Index(value = ["userId", "questionId"], unique = true)]
)
data class MistakeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val questionId: String,
    val questionTitle: String,
    val difficulty: String,
    val topic: String,
    val errorReason: String = "Logic / Edge Case Review",
    val failedCodeSnippet: String = "",
    val language: String = "Kotlin",
    val userNotes: String = "",
    val reviewCount: Int = 0,
    val lastReviewedAt: Long? = null,
    val savedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["userId", "questionId"], unique = true)]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val questionId: String,
    val questionTitle: String,
    val difficulty: String,
    val topic: String,
    val savedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "user_achievements",
    indices = [Index(value = ["userId", "achievementKey"], unique = true)]
)
data class UserAchievementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val achievementKey: String,
    val unlockedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "user_badges",
    indices = [Index(value = ["userId", "badgeKey"], unique = true)]
)
data class UserBadgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val badgeKey: String,
    val unlockedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "xp_transactions",
    indices = [Index(value = ["userId", "actionKey"], unique = true)]
)
data class XpTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val actionKey: String,
    val reason: String,
    val xpAmount: Int,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "app_notifications",
    indices = [Index(value = ["userId", "uniqueEventKey"], unique = true)]
)
data class AppNotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val uniqueEventKey: String,
    val category: String, // ACHIEVEMENT, BADGE, LEVEL_UP, DAILY_GOAL, STREAK, DAILY_CHALLENGE
    val title: String,
    val message: String,
    val isRead: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "ai_history")
data class AIHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val language: String,
    val prompt: String,
    val responseText: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class DifficultyBreakdown(
    val easySolved: Int = 0,
    val easyTotal: Int = 8,
    val mediumSolved: Int = 0,
    val mediumTotal: Int = 8,
    val hardSolved: Int = 0,
    val hardTotal: Int = 8
)

data class ProgressAnalyticsSnapshot(
    val totalSolved: Int = 0,
    val totalTopicCapacity: Int = 80,
    val overallMasteryPercent: Int = 0,
    val uniqueQuestionsAttempted: Int = 0,
    val totalSubmissions: Int = 0,
    val passedSubmissions: Int = 0,
    val accuracyPercent: Int = 0,
    val solveConversionPercent: Int = 0,
    val difficultyBreakdown: DifficultyBreakdown = DifficultyBreakdown(),
    val topics: List<TopicProgressEntity> = emptyList(),
    val recentAttempts: List<AttemptEntity> = emptyList(),
    val solvedQuestions: List<SolvedQuestionEntity> = emptyList()
)

data class PublicProfile(
    val usernameSlug: String,
    val displayName: String,
    val level: Int,
    val xp: Int,
    val currentStreak: Int,
    val solvedProblems: Int,
    val profileUrl: String
)
