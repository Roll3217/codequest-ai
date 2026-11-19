package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import com.example.model.AIHistoryEntity
import com.example.model.AppNotificationEntity
import com.example.model.AttemptEntity
import com.example.model.BookmarkEntity
import com.example.model.DailyGoalEntity
import com.example.model.MistakeEntity
import com.example.model.QuestionEntity
import com.example.model.SolvedQuestionEntity
import com.example.model.TopicProgressEntity
import com.example.model.UserAchievementEntity
import com.example.model.UserBadgeEntity
import com.example.model.UserEntity
import com.example.model.XpTransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE LOWER(email) = LOWER(:email) LIMIT 1")
    suspend fun getUserByEmail(email: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :userId LIMIT 1")
    suspend fun getUserById(userId: Long): UserEntity?

    @Query("SELECT * FROM users WHERE id = :userId LIMIT 1")
    fun observeUserById(userId: Long): Flow<UserEntity?>

    @Query("SELECT * FROM users ORDER BY id ASC LIMIT 1")
    suspend fun getFirstUser(): UserEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUser(user: UserEntity): Long

    @Update
    suspend fun updateUser(user: UserEntity)

    @Query("UPDATE users SET interests = :interests WHERE id = :userId")
    suspend fun updateInterests(userId: Long, interests: String)

    @Query("UPDATE users SET preferredLanguage = :language WHERE id = :userId")
    suspend fun updatePreferredLanguage(userId: Long, language: String)

    @Query("DELETE FROM users WHERE id = :userId")
    suspend fun deleteUserById(userId: Long): Int
}

@Dao
interface QuestionDao {
    @Query("SELECT COUNT(*) FROM questions")
    suspend fun getQuestionCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestions(questions: List<QuestionEntity>)

    @Query("SELECT * FROM questions ORDER BY id ASC")
    fun observeAllQuestions(): Flow<List<QuestionEntity>>

    @Query("SELECT * FROM questions ORDER BY id ASC")
    suspend fun getAllQuestions(): List<QuestionEntity>

    @Query("SELECT * FROM questions WHERE id = :questionId LIMIT 1")
    suspend fun getQuestionById(questionId: String): QuestionEntity?

    @Query("SELECT * FROM questions WHERE id = :questionId LIMIT 1")
    fun observeQuestionById(questionId: String): Flow<QuestionEntity?>

    @Query("SELECT * FROM questions WHERE LOWER(difficulty) = LOWER(:difficulty) ORDER BY id ASC")
    suspend fun getQuestionsByDifficulty(difficulty: String): List<QuestionEntity>
}

@Dao
interface ProgressDao {
    // Topic Progress (8 topics, each 0/10)
    @Query("SELECT * FROM topic_progress WHERE userId = :userId ORDER BY topicIndex ASC")
    fun observeTopicProgress(userId: Long): Flow<List<TopicProgressEntity>>

    @Query("SELECT * FROM topic_progress WHERE userId = :userId ORDER BY topicIndex ASC")
    suspend fun getTopicProgressList(userId: Long): List<TopicProgressEntity>

    @Query("SELECT * FROM topic_progress WHERE userId = :userId AND topicName = :topicName LIMIT 1")
    suspend fun getTopicProgress(userId: Long, topicName: String): TopicProgressEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTopicProgressList(topics: List<TopicProgressEntity>)

    @Update
    suspend fun updateTopicProgress(topicProgress: TopicProgressEntity)

    @Query("SELECT COALESCE(SUM(solvedCount), 0) FROM topic_progress WHERE userId = :userId")
    fun observeTotalSolvedCount(userId: Long): Flow<Int>

    // Solved Questions (Unique per user + questionId)
    @Query("SELECT * FROM solved_questions WHERE userId = :userId ORDER BY solvedAt DESC")
    fun observeSolvedQuestions(userId: Long): Flow<List<SolvedQuestionEntity>>

    @Query("SELECT * FROM solved_questions WHERE userId = :userId ORDER BY solvedAt DESC")
    suspend fun getSolvedQuestions(userId: Long): List<SolvedQuestionEntity>

    @Query("SELECT * FROM solved_questions WHERE userId = :userId AND questionId = :questionId LIMIT 1")
    suspend fun getSolvedQuestion(userId: Long, questionId: String): SolvedQuestionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSolvedQuestion(solved: SolvedQuestionEntity): Long

    // Daily Goals
    @Query("SELECT * FROM daily_goals WHERE userId = :userId AND dateString = :dateString ORDER BY id ASC")
    fun observeDailyGoals(userId: Long, dateString: String): Flow<List<DailyGoalEntity>>

    @Query("SELECT * FROM daily_goals WHERE userId = :userId AND dateString = :dateString ORDER BY id ASC")
    suspend fun getDailyGoals(userId: Long, dateString: String): List<DailyGoalEntity>

    @Query("SELECT * FROM daily_goals WHERE userId = :userId AND dateString = :dateString AND goalKey = :goalKey LIMIT 1")
    suspend fun getDailyGoalByKey(userId: Long, dateString: String, goalKey: String): DailyGoalEntity?

    @Query("SELECT COUNT(*) FROM daily_goals WHERE userId = :userId AND isCompleted = 1")
    suspend fun getTotalCompletedDailyGoalsAllTime(userId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDailyGoals(goals: List<DailyGoalEntity>)

    @Update
    suspend fun updateDailyGoal(goal: DailyGoalEntity)

    // Bookmarks
    @Query("SELECT * FROM bookmarks WHERE userId = :userId ORDER BY savedAt DESC")
    fun observeBookmarks(userId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE userId = :userId ORDER BY savedAt DESC")
    suspend fun getBookmarks(userId: Long): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE userId = :userId AND questionId = :questionId LIMIT 1")
    suspend fun getBookmark(userId: Long, questionId: String): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE userId = :userId AND questionId = :questionId")
    suspend fun deleteBookmark(userId: Long, questionId: String)

    // Mistakes Notebook
    @Query("SELECT * FROM mistakes WHERE userId = :userId ORDER BY savedAt DESC")
    fun observeMistakes(userId: Long): Flow<List<MistakeEntity>>

    @Query("SELECT * FROM mistakes WHERE userId = :userId ORDER BY savedAt DESC")
    suspend fun getMistakes(userId: Long): List<MistakeEntity>

    @Query("SELECT * FROM mistakes WHERE userId = :userId AND questionId = :questionId LIMIT 1")
    suspend fun getMistake(userId: Long, questionId: String): MistakeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMistake(mistake: MistakeEntity)

    @Update
    suspend fun updateMistake(mistake: MistakeEntity)

    @Query("DELETE FROM mistakes WHERE userId = :userId AND questionId = :questionId")
    suspend fun deleteMistake(userId: Long, questionId: String)

    // Attempts / Activity History
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttempt(attempt: AttemptEntity): Long

    @Update
    suspend fun updateAttempt(attempt: AttemptEntity)

    @Query("SELECT * FROM attempts WHERE userId = :userId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestAttemptForUser(userId: Long): AttemptEntity?

    @Query("SELECT * FROM attempts WHERE userId = :userId ORDER BY timestamp DESC LIMIT 15")
    fun observeRecentAttempts(userId: Long): Flow<List<AttemptEntity>>

    @Query("SELECT * FROM attempts WHERE userId = :userId ORDER BY timestamp DESC")
    fun observeAllAttempts(userId: Long): Flow<List<AttemptEntity>>

    @Query("SELECT * FROM attempts WHERE userId = :userId ORDER BY timestamp DESC")
    suspend fun getAllAttempts(userId: Long): List<AttemptEntity>

    // XP Transactions (Prevents duplicate XP for the same actionKey)
    @Query("SELECT * FROM xp_transactions WHERE userId = :userId AND actionKey = :actionKey LIMIT 1")
    suspend fun getXpTransaction(userId: Long, actionKey: String): XpTransactionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertXpTransaction(tx: XpTransactionEntity): Long

    @Query("SELECT * FROM xp_transactions WHERE userId = :userId ORDER BY createdAt DESC")
    fun observeXpTransactions(userId: Long): Flow<List<XpTransactionEntity>>

    // Achievements
    @Query("SELECT * FROM user_achievements WHERE userId = :userId ORDER BY unlockedAt DESC")
    fun observeUnlockedAchievements(userId: Long): Flow<List<UserAchievementEntity>>

    @Query("SELECT * FROM user_achievements WHERE userId = :userId ORDER BY unlockedAt DESC")
    suspend fun getUnlockedAchievements(userId: Long): List<UserAchievementEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUserAchievement(achievement: UserAchievementEntity): Long

    // Badges
    @Query("SELECT * FROM user_badges WHERE userId = :userId ORDER BY unlockedAt DESC")
    fun observeUnlockedBadges(userId: Long): Flow<List<UserBadgeEntity>>

    @Query("SELECT * FROM user_badges WHERE userId = :userId ORDER BY unlockedAt DESC")
    suspend fun getUnlockedBadges(userId: Long): List<UserBadgeEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUserBadge(badge: UserBadgeEntity): Long

    // Notifications
    @Query("SELECT * FROM app_notifications WHERE userId = :userId ORDER BY createdAt DESC")
    fun observeNotifications(userId: Long): Flow<List<AppNotificationEntity>>

    @Query("SELECT * FROM app_notifications WHERE userId = :userId ORDER BY createdAt DESC")
    suspend fun getNotifications(userId: Long): List<AppNotificationEntity>

    @Query("SELECT COUNT(*) FROM app_notifications WHERE userId = :userId AND isRead = 0")
    fun observeUnreadNotificationCount(userId: Long): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNotification(notification: AppNotificationEntity): Long

    @Query("UPDATE app_notifications SET isRead = 1 WHERE id = :notificationId")
    suspend fun markNotificationRead(notificationId: Long)

    @Query("UPDATE app_notifications SET isRead = 1 WHERE userId = :userId")
    suspend fun markAllNotificationsRead(userId: Long)

    @Query("DELETE FROM app_notifications WHERE userId = :userId AND isRead = 1")
    suspend fun deleteReadNotifications(userId: Long)

    @Query("DELETE FROM topic_progress WHERE userId = :userId")
    suspend fun deleteTopicProgressForUser(userId: Long)

    @Query("DELETE FROM solved_questions WHERE userId = :userId")
    suspend fun deleteSolvedQuestionsForUser(userId: Long)

    @Query("DELETE FROM daily_goals WHERE userId = :userId")
    suspend fun deleteDailyGoalsForUser(userId: Long)

    @Query("DELETE FROM bookmarks WHERE userId = :userId")
    suspend fun deleteBookmarksForUser(userId: Long)

    @Query("DELETE FROM mistakes WHERE userId = :userId")
    suspend fun deleteMistakesForUser(userId: Long)

    @Query("DELETE FROM attempts WHERE userId = :userId")
    suspend fun deleteAttemptsForUser(userId: Long)

    @Query("DELETE FROM xp_transactions WHERE userId = :userId")
    suspend fun deleteXpTransactionsForUser(userId: Long)

    @Query("DELETE FROM user_achievements WHERE userId = :userId")
    suspend fun deleteAchievementsForUser(userId: Long)

    @Query("DELETE FROM user_badges WHERE userId = :userId")
    suspend fun deleteBadgesForUser(userId: Long)

    @Query("DELETE FROM app_notifications WHERE userId = :userId")
    suspend fun deleteAllNotificationsForUser(userId: Long)

    @Query("DELETE FROM ai_history WHERE userId = :userId")
    suspend fun deleteAiHistoryForUser(userId: Long)
}

@Database(
    entities = [
        UserEntity::class,
        QuestionEntity::class,
        AttemptEntity::class,
        SolvedQuestionEntity::class,
        TopicProgressEntity::class,
        DailyGoalEntity::class,
        MistakeEntity::class,
        BookmarkEntity::class,
        UserAchievementEntity::class,
        UserBadgeEntity::class,
        XpTransactionEntity::class,
        AppNotificationEntity::class,
        AIHistoryEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class CodeQuestDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun questionDao(): QuestionDao
    abstract fun progressDao(): ProgressDao

    companion object {
        @Volatile
        private var INSTANCE: CodeQuestDatabase? = null

        fun getInstance(context: Context): CodeQuestDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CodeQuestDatabase::class.java,
                    "codequest_ai.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = false)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
