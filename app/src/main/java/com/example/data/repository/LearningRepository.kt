package com.example.data.repository

import com.example.data.local.ProgressDao
import com.example.data.local.QuestionBankSeed
import com.example.data.local.QuestionDao
import com.example.data.local.SessionPreferencesManager
import com.example.data.local.UserDao
import com.example.model.AppNotificationEntity
import com.example.model.AttemptEntity
import com.example.model.BookmarkEntity
import com.example.model.DailyChallengeStatus
import com.example.model.DailyGoalEntity
import com.example.model.DifficultyBreakdown
import com.example.model.MistakeEntity
import com.example.model.ProgressAnalyticsSnapshot
import com.example.model.QuestionEntity
import com.example.model.QuestionWithStatus
import com.example.model.SolvedQuestionEntity
import com.example.model.TopicProgressEntity
import com.example.model.XpTransactionEntity
import com.example.ui.achievements.AchievementWithStatus
import com.example.ui.achievements.BadgeWithStatus
import com.example.ui.achievements.GamificationCatalog
import com.example.ui.achievements.LevelProgressionEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

class LearningRepository(
    private val userDao: UserDao,
    private val questionDao: QuestionDao,
    private val progressDao: ProgressDao,
    private val sessionPreferences: SessionPreferencesManager? = null,
    private val supabaseRepository: com.example.data.remote.supabase.SupabaseRepository? = null
) {
    fun progressDaoDirect(): ProgressDao = progressDao

    @Volatile
    var onNotificationCreated: (suspend (AppNotificationEntity) -> Unit)? = null

    companion object {
        const val GOAL_SOLVE_EASY = "SOLVE_EASY"
        const val GOAL_SOLVE_MEDIUM = "SOLVE_MEDIUM"
        const val GOAL_SOLVE_HARD = "SOLVE_HARD"
        const val GOAL_DAILY_CHALLENGE = "DAILY_CHALLENGE"
        const val GOAL_CONCEPT_QUIZ = "CONCEPT_QUIZ"
        const val GOAL_LEARN_15_MIN = "LEARN_15_MIN"
        const val GOAL_REVIEW_MISTAKE = "REVIEW_MISTAKE"
        const val GOAL_ASK_AI_MENTOR = "ASK_AI_MENTOR"

        const val NOTIF_CAT_ACHIEVEMENT = "ACHIEVEMENT"
        const val NOTIF_CAT_BADGE = "BADGE"
        const val NOTIF_CAT_LEVEL_UP = "LEVEL_UP"
        const val NOTIF_CAT_DAILY_GOAL = "DAILY_GOAL"
        const val NOTIF_CAT_STREAK = "STREAK"
        const val NOTIF_CAT_DAILY_CHALLENGE = "DAILY_CHALLENGE"
        const val NOTIF_CAT_ROAST = "ROAST"
        const val NOTIF_CAT_AVATAR = "AVATAR"

        fun todayDateString(): String {
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        }

        fun yesterdayDateString(): String {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -1)
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        }
    }

    /**
     * Ensures the Question Bank, the 8 Topic Learning Paths (starting at 0/10),
     * and Today's Daily Goals exist in Room for the given user.
     *
     * IMPORTANT: Simply opening the app NEVER increases streak, XP, or topic progress.
     */
    suspend fun ensureInitializedForUser(userId: Long, currentDate: String = todayDateString()) {
        if (questionDao.getQuestionCount() < QuestionBankSeed.INITIAL_QUESTIONS.size) {
            questionDao.insertQuestions(QuestionBankSeed.INITIAL_QUESTIONS)
        }
        if (userId <= 0L) return

        val existingTopics = progressDao.getTopicProgressList(userId)
        if (existingTopics.size < 8) {
            val defaultTopics = listOf(
                TopicProgressEntity(userId = userId, topicIndex = 1, topicName = "Programming Basics", description = "Variables, I/O, data types, and operators", solvedCount = 0, maxCount = 10),
                TopicProgressEntity(userId = userId, topicIndex = 2, topicName = "Conditions", description = "If-else branches, switch/when, boolean logic", solvedCount = 0, maxCount = 10),
                TopicProgressEntity(userId = userId, topicIndex = 3, topicName = "Loops", description = "For, while, nested iterations, and control flow", solvedCount = 0, maxCount = 10),
                TopicProgressEntity(userId = userId, topicIndex = 4, topicName = "Strings", description = "Manipulation, palindromes, substrings, parsing", solvedCount = 0, maxCount = 10),
                TopicProgressEntity(userId = userId, topicIndex = 5, topicName = "Arrays", description = "Traversal, two-pointers, sliding window, sorting", solvedCount = 0, maxCount = 10),
                TopicProgressEntity(userId = userId, topicIndex = 6, topicName = "Number Theory", description = "Primes, GCD/LCM, modular arithmetic, digits", solvedCount = 0, maxCount = 10),
                TopicProgressEntity(userId = userId, topicIndex = 7, topicName = "Patterns", description = "Star pyramids, numeric grids, symmetry logic", solvedCount = 0, maxCount = 10),
                TopicProgressEntity(userId = userId, topicIndex = 8, topicName = "Problem Solving", description = "Recursion, hashing, greedy, and algorithmic quests", solvedCount = 0, maxCount = 10)
            )
            progressDao.insertTopicProgressList(defaultTopics)
        }

        val existingGoals = progressDao.getDailyGoals(userId, currentDate)
        if (existingGoals.size < 8) {
            val initialGoals = listOf(
                DailyGoalEntity(userId = userId, dateString = currentDate, goalKey = GOAL_SOLVE_EASY, title = "Solve 1 Easy Problem", xpReward = 20),
                DailyGoalEntity(userId = userId, dateString = currentDate, goalKey = GOAL_SOLVE_MEDIUM, title = "Solve 1 Medium Problem", xpReward = 30),
                DailyGoalEntity(userId = userId, dateString = currentDate, goalKey = GOAL_SOLVE_HARD, title = "Solve 1 Hard Problem", xpReward = 45),
                DailyGoalEntity(userId = userId, dateString = currentDate, goalKey = GOAL_DAILY_CHALLENGE, title = "Complete Daily Challenge", xpReward = 50),
                DailyGoalEntity(userId = userId, dateString = currentDate, goalKey = GOAL_CONCEPT_QUIZ, title = "Complete Concept Quiz", xpReward = 10),
                DailyGoalEntity(userId = userId, dateString = currentDate, goalKey = GOAL_LEARN_15_MIN, title = "Learn for 15 Minutes", xpReward = 15),
                DailyGoalEntity(userId = userId, dateString = currentDate, goalKey = GOAL_REVIEW_MISTAKE, title = "Review One Mistake", xpReward = 15),
                DailyGoalEntity(userId = userId, dateString = currentDate, goalKey = GOAL_ASK_AI_MENTOR, title = "Ask AI Mentor", xpReward = 10)
            )
            progressDao.insertDailyGoals(initialGoals)
        }

        // Check if user missed consecutive days (reset currentStreak to 0 if lastQualifyingDate is older than yesterday)
        val user = userDao.getUserById(userId)
        if (user != null && user.currentStreak > 0 && !user.lastQualifyingDate.isNullOrBlank()) {
            val yesterday = previousDateStringFrom(currentDate)
            if (user.lastQualifyingDate != currentDate && user.lastQualifyingDate != yesterday) {
                userDao.updateUser(user.copy(currentStreak = 0))
            }
        }
    }

    fun observeQuestionsWithStatus(userId: Long): Flow<List<QuestionWithStatus>> {
        return combine(
            questionDao.observeAllQuestions(),
            progressDao.observeSolvedQuestions(userId),
            progressDao.observeBookmarks(userId),
            progressDao.observeMistakes(userId)
        ) { questions, solvedList, bookmarks, mistakes ->
            val solvedIds = solvedList.map { it.questionId }.toSet()
            val bookmarkedIds = bookmarks.map { it.questionId }.toSet()
            val mistakeIds = mistakes.map { it.questionId }.toSet()

            questions.map { q ->
                QuestionWithStatus(
                    question = q,
                    isSolved = q.id in solvedIds,
                    isBookmarked = q.id in bookmarkedIds,
                    isInMistakes = q.id in mistakeIds
                )
            }
        }
    }

    fun observeQuestionWithStatusById(userId: Long, questionId: String): Flow<QuestionWithStatus?> {
        return combine(
            questionDao.observeQuestionById(questionId),
            progressDao.observeSolvedQuestions(userId),
            progressDao.observeBookmarks(userId),
            progressDao.observeMistakes(userId)
        ) { question, solvedList, bookmarks, mistakes ->
            question?.let { q ->
                QuestionWithStatus(
                    question = q,
                    isSolved = solvedList.any { it.questionId == q.id },
                    isBookmarked = bookmarks.any { it.questionId == q.id },
                    isInMistakes = mistakes.any { it.questionId == q.id }
                )
            }
        }
    }

    fun observeProgressAnalytics(userId: Long): Flow<ProgressAnalyticsSnapshot> {
        return combine(
            questionDao.observeAllQuestions(),
            progressDao.observeSolvedQuestions(userId),
            progressDao.observeTopicProgress(userId),
            progressDao.observeAllAttempts(userId)
        ) { allQuestions, solvedList, topics, attempts ->
            val pool = allQuestions.ifEmpty { QuestionBankSeed.INITIAL_QUESTIONS }
            val easyTotal = pool.count { it.difficulty.equals("Easy", ignoreCase = true) }.coerceAtLeast(8)
            val mediumTotal = pool.count { it.difficulty.equals("Medium", ignoreCase = true) }.coerceAtLeast(8)
            val hardTotal = pool.count { it.difficulty.equals("Hard", ignoreCase = true) }.coerceAtLeast(8)

            val easySolved = solvedList.count { it.difficulty.equals("Easy", ignoreCase = true) }
            val mediumSolved = solvedList.count { it.difficulty.equals("Medium", ignoreCase = true) }
            val hardSolved = solvedList.count { it.difficulty.equals("Hard", ignoreCase = true) }

            val totalSolved = solvedList.size
            val totalCapacity = topics.sumOf { it.maxCount }.coerceAtLeast(80)
            val overallMasteryPct = ((totalSolved.coerceAtMost(totalCapacity)) * 100) / totalCapacity

            val uniqueAttemptedIds = attempts.map { it.questionId }.toSet()
            val uniqueAttemptedCount = max(uniqueAttemptedIds.size, totalSolved)
            val totalSubmissions = attempts.size
            val passedSubmissions = attempts.count { it.passed }

            val accuracyPct = if (totalSubmissions == 0) {
                0
            } else {
                ((passedSubmissions * 100) / totalSubmissions).coerceIn(0, 100)
            }

            val solveConversionPct = if (uniqueAttemptedCount == 0) {
                0
            } else {
                ((totalSolved.coerceAtMost(uniqueAttemptedCount) * 100) / uniqueAttemptedCount).coerceIn(0, 100)
            }

            ProgressAnalyticsSnapshot(
                totalSolved = totalSolved,
                totalTopicCapacity = totalCapacity,
                overallMasteryPercent = overallMasteryPct,
                uniqueQuestionsAttempted = uniqueAttemptedCount,
                totalSubmissions = totalSubmissions,
                passedSubmissions = passedSubmissions,
                accuracyPercent = accuracyPct,
                solveConversionPercent = solveConversionPct,
                difficultyBreakdown = DifficultyBreakdown(
                    easySolved = easySolved,
                    easyTotal = easyTotal,
                    mediumSolved = mediumSolved,
                    mediumTotal = mediumTotal,
                    hardSolved = hardSolved,
                    hardTotal = hardTotal
                ),
                topics = topics,
                recentAttempts = attempts.take(20),
                solvedQuestions = solvedList
            )
        }
    }

    fun observeAchievementsWithStatus(userId: Long): Flow<List<AchievementWithStatus>> {
        return combine(
            userDao.observeUserById(userId),
            progressDao.observeSolvedQuestions(userId),
            progressDao.observeTopicProgress(userId),
            progressDao.observeAllAttempts(userId),
            progressDao.observeUnlockedAchievements(userId)
        ) { user, solvedList, topics, attempts, unlockedList ->
            val unlockedMap = unlockedList.associateBy { it.achievementKey }
            val solvedCount = solvedList.size
            val bestTopicSolved = topics.maxOfOrNull { it.solvedCount } ?: 0
            val maxStreak = max(user?.currentStreak ?: 0, user?.longestStreak ?: 0)
            val currentXp = user?.xp ?: 0
            val accuracyPct = if (attempts.size >= 5) {
                (attempts.count { it.passed } * 100) / attempts.size
            } else {
                0
            }

            GamificationCatalog.ALL_ACHIEVEMENTS.map { def ->
                val unlockedEntity = unlockedMap[def.key]
                val currentVal = when (def.key) {
                    GamificationCatalog.ACH_FIRST_SOLVE,
                    GamificationCatalog.ACH_SOLVE_5,
                    GamificationCatalog.ACH_SOLVE_10,
                    GamificationCatalog.ACH_SOLVE_50,
                    GamificationCatalog.ACH_SOLVE_100 -> solvedCount
                    GamificationCatalog.ACH_FIRST_DAILY_CHALLENGE -> if (unlockedEntity != null) 1 else 0
                    GamificationCatalog.ACH_STREAK_3,
                    GamificationCatalog.ACH_STREAK_7 -> maxStreak
                    GamificationCatalog.ACH_TOPIC_COMPLETION -> bestTopicSolved
                    GamificationCatalog.ACH_HIGH_ACCURACY -> accuracyPct
                    GamificationCatalog.ACH_XP_100,
                    GamificationCatalog.ACH_XP_500 -> currentXp
                    else -> 0
                }
                AchievementWithStatus(
                    definition = def,
                    isUnlocked = unlockedEntity != null,
                    unlockedAt = unlockedEntity?.unlockedAt,
                    currentValue = currentVal
                )
            }
        }
    }

    fun observeBadgesWithStatus(userId: Long): Flow<List<BadgeWithStatus>> {
        return progressDao.observeUnlockedBadges(userId).combine(
            userDao.observeUserById(userId)
        ) { unlockedBadges, _ ->
            val unlockedMap = unlockedBadges.associateBy { it.badgeKey }
            GamificationCatalog.ALL_BADGES.map { def ->
                val entity = unlockedMap[def.key]
                BadgeWithStatus(
                    definition = def,
                    isUnlocked = entity != null,
                    unlockedAt = entity?.unlockedAt
                )
            }
        }
    }

    suspend fun getQuestionById(questionId: String): QuestionEntity? {
        if (questionDao.getQuestionCount() < QuestionBankSeed.INITIAL_QUESTIONS.size) {
            questionDao.insertQuestions(QuestionBankSeed.INITIAL_QUESTIONS)
        }
        return questionDao.getQuestionById(questionId)
    }

    suspend fun getRandomQuestionByDifficulty(difficulty: String): QuestionEntity? {
        if (questionDao.getQuestionCount() < QuestionBankSeed.INITIAL_QUESTIONS.size) {
            questionDao.insertQuestions(QuestionBankSeed.INITIAL_QUESTIONS)
        }
        val matching = questionDao.getQuestionsByDifficulty(difficulty)
        return if (matching.isNotEmpty()) matching.random() else questionDao.getAllQuestions().firstOrNull()
    }

    fun observeDailyChallenge(userId: Long): Flow<DailyChallengeStatus?> {
        val today = todayDateString()
        return combine(
            questionDao.observeAllQuestions(),
            progressDao.observeSolvedQuestions(userId)
        ) { questions, solvedList ->
            val pool = questions.ifEmpty { QuestionBankSeed.INITIAL_QUESTIONS }
            if (pool.isEmpty()) return@combine null
            val index = abs(today.hashCode()) % pool.size
            val dailyQuestion = pool[index]
            val isCompleted = solvedList.any { it.questionId == dailyQuestion.id }
            DailyChallengeStatus(
                dateString = today,
                question = dailyQuestion,
                xpReward = 50,
                isCompleted = isCompleted
            )
        }
    }

    fun observeTopicProgress(userId: Long): Flow<List<TopicProgressEntity>> {
        return progressDao.observeTopicProgress(userId)
    }

    fun observeDailyGoals(userId: Long): Flow<List<DailyGoalEntity>> {
        return progressDao.observeDailyGoals(userId, todayDateString())
    }

    fun observeRecentAttempts(userId: Long): Flow<List<AttemptEntity>> {
        return progressDao.observeRecentAttempts(userId)
    }

    fun observeAllAttempts(userId: Long): Flow<List<AttemptEntity>> {
        return progressDao.observeAllAttempts(userId)
    }

    fun observeMistakes(userId: Long): Flow<List<MistakeEntity>> {
        return progressDao.observeMistakes(userId)
    }

    fun observeBookmarks(userId: Long): Flow<List<BookmarkEntity>> {
        return progressDao.observeBookmarks(userId)
    }

    fun observeNotifications(userId: Long): Flow<List<AppNotificationEntity>> {
        return progressDao.observeNotifications(userId)
    }

    suspend fun getNotifications(userId: Long): List<AppNotificationEntity> {
        return progressDao.getNotifications(userId)
    }

    fun observeUnreadNotificationCount(userId: Long): Flow<Int> {
        return progressDao.observeUnreadNotificationCount(userId)
    }

    suspend fun markNotificationRead(notificationId: Long) {
        progressDao.markNotificationRead(notificationId)
    }

    suspend fun markAllNotificationsRead(userId: Long) {
        progressDao.markAllNotificationsRead(userId)
    }

    suspend fun clearReadNotifications(userId: Long) {
        progressDao.deleteReadNotifications(userId)
    }

    /**
     * Toggles whether a question is bookmarked by the current user.
     */
    suspend fun toggleBookmark(userId: Long, question: QuestionEntity): Boolean {
        val existing = progressDao.getBookmark(userId, question.id)
        val userEmail = userDao.getUserById(userId)?.email
        return if (existing != null) {
            progressDao.deleteBookmark(userId, question.id)
            if (!userEmail.isNullOrBlank() && supabaseRepository != null && supabaseRepository.isConfigured()) {
                supabaseRepository.pushBookmarkChange(userEmail = userEmail, removedQuestionId = question.id)
            }
            false
        } else {
            val newBookmark = BookmarkEntity(
                userId = userId,
                questionId = question.id,
                questionTitle = question.title,
                difficulty = question.difficulty,
                topic = question.topic
            )
            progressDao.insertBookmark(newBookmark)
            if (!userEmail.isNullOrBlank() && supabaseRepository != null && supabaseRepository.isConfigured()) {
                supabaseRepository.pushBookmarkChange(userEmail = userEmail, bookmark = newBookmark)
            }
            true
        }
    }

    suspend fun removeBookmarkByQuestionId(userId: Long, questionId: String) {
        progressDao.deleteBookmark(userId, questionId)
        val userEmail = userDao.getUserById(userId)?.email
        if (!userEmail.isNullOrBlank() && supabaseRepository != null && supabaseRepository.isConfigured()) {
            supabaseRepository.pushBookmarkChange(userEmail = userEmail, removedQuestionId = questionId)
        }
    }

    /**
     * Toggles whether a question is saved in the user's Mistakes Notebook.
     */
    suspend fun toggleMistake(
        userId: Long,
        question: QuestionEntity,
        errorReason: String = "Flagged for Concept / Edge-Case Review",
        failedCodeSnippet: String = "",
        language: String = "Kotlin",
        notes: String = ""
    ): Boolean {
        val existing = progressDao.getMistake(userId, question.id)
        val userEmail = userDao.getUserById(userId)?.email
        return if (existing != null) {
            progressDao.deleteMistake(userId, question.id)
            if (!userEmail.isNullOrBlank() && supabaseRepository != null && supabaseRepository.isConfigured()) {
                supabaseRepository.pushMistakeChange(userEmail = userEmail, removedQuestionId = question.id)
            }
            false
        } else {
            val newMistake = MistakeEntity(
                userId = userId,
                questionId = question.id,
                questionTitle = question.title,
                difficulty = question.difficulty,
                topic = question.topic,
                errorReason = errorReason,
                failedCodeSnippet = failedCodeSnippet,
                language = language,
                userNotes = notes
            )
            progressDao.insertMistake(newMistake)
            if (!userEmail.isNullOrBlank() && supabaseRepository != null && supabaseRepository.isConfigured()) {
                supabaseRepository.pushMistakeChange(userEmail = userEmail, mistake = newMistake)
            }
            true
        }
    }

    suspend fun saveOrUpdateMistakeNotes(
        userId: Long,
        questionId: String,
        newNotes: String,
        errorReason: String? = null
    ) {
        val existing = progressDao.getMistake(userId, questionId) ?: return
        val updated = existing.copy(
            userNotes = newNotes,
            errorReason = errorReason ?: existing.errorReason
        )
        progressDao.updateMistake(updated)
        val userEmail = userDao.getUserById(userId)?.email
        if (!userEmail.isNullOrBlank() && supabaseRepository != null && supabaseRepository.isConfigured()) {
            supabaseRepository.pushMistakeChange(userEmail = userEmail, mistake = updated)
        }
    }

    suspend fun removeMistakeByQuestionId(userId: Long, questionId: String) {
        progressDao.deleteMistake(userId, questionId)
        val userEmail = userDao.getUserById(userId)?.email
        if (!userEmail.isNullOrBlank() && supabaseRepository != null && supabaseRepository.isConfigured()) {
            supabaseRepository.pushMistakeChange(userEmail = userEmail, removedQuestionId = questionId)
        }
    }

    /**
     * Records a real Mistake Review action when a user actively reviews a problem from their Mistakes Notebook.
     */
    suspend fun completeMistakeReviewAction(
        userId: Long,
        questionId: String? = null,
        currentDate: String = todayDateString()
    ): Boolean {
        if (!questionId.isNullOrBlank()) {
            val mistake = progressDao.getMistake(userId, questionId)
            if (mistake != null) {
                progressDao.updateMistake(
                    mistake.copy(
                        reviewCount = mistake.reviewCount + 1,
                        lastReviewedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        unlockBadgeIfEligible(userId, GamificationCatalog.BADGE_BUG_HUNTER)
        return markDailyGoalCompleted(userId, GOAL_REVIEW_MISTAKE, currentDate)
    }

    /**
     * Records a real Concept Quiz completion when the user passes the interactive 3-question concept quiz.
     */
    suspend fun completeConceptQuizAction(
        userId: Long,
        currentDate: String = todayDateString()
    ): Boolean {
        unlockBadgeIfEligible(userId, GamificationCatalog.BADGE_CONCEPT_SCHOLAR)
        return markDailyGoalCompleted(userId, GOAL_CONCEPT_QUIZ, currentDate)
    }

    /**
     * Records a real 15-minute study session completion.
     */
    suspend fun completeStudySessionAction(
        userId: Long,
        currentDate: String = todayDateString()
    ): Boolean {
        return markDailyGoalCompleted(userId, GOAL_LEARN_15_MIN, currentDate)
    }

    /**
     * Records a genuine AI Mentor question action.
     */
    suspend fun completeAskAiMentorAction(
        userId: Long,
        currentDate: String = todayDateString()
    ): Boolean {
        return markDailyGoalCompleted(userId, GOAL_ASK_AI_MENTOR, currentDate)
    }

    /**
     * Records an attempt in history.
     *
     * IMPORTANT RULES:
     * 1. If passed == false, SolvedCount and TopicProgress are NEVER incremented.
     * 2. If recordMistakeOnFailure == true and passed == false, records/updates the question in the Mistakes Notebook.
     * 3. Avoids duplicate consecutive history entries when identical code is submitted rapidly.
     * 4. If passed == true, increments TopicProgress (+1 up to 10/10) ONLY if this exact question has not been solved before.
     */
    suspend fun recordSubmissionAttempt(
        userId: Long,
        question: QuestionEntity,
        language: String,
        sourceCode: String,
        passed: Boolean,
        statusMessage: String,
        recordMistakeOnFailure: Boolean = false,
        currentDate: String = todayDateString()
    ) {
        ensureInitializedForUser(userId, currentDate)

        // Avoid duplicate consecutive identical history entries within 60 seconds
        val latestAttempt = progressDao.getLatestAttemptForUser(userId)
        val now = System.currentTimeMillis()
        if (latestAttempt != null &&
            latestAttempt.questionId == question.id &&
            latestAttempt.language.equals(language, ignoreCase = true) &&
            latestAttempt.submittedCode.trim() == sourceCode.trim() &&
            latestAttempt.passed == passed &&
            (now - latestAttempt.timestamp) < 60_000L
        ) {
            progressDao.updateAttempt(
                latestAttempt.copy(
                    statusMessage = statusMessage,
                    timestamp = now
                )
            )
        } else {
            progressDao.insertAttempt(
                AttemptEntity(
                    userId = userId,
                    questionId = question.id,
                    questionTitle = question.title,
                    topic = question.topic,
                    difficulty = question.difficulty,
                    language = language,
                    submittedCode = sourceCode,
                    passed = passed,
                    statusMessage = statusMessage,
                    timestamp = now
                )
            )
        }

        // Check multi-language badge
        val allAttempts = progressDao.getAllAttempts(userId)
        val distinctLanguages = allAttempts.map { it.language.lowercase(Locale.ROOT) }.toSet()
        if (distinctLanguages.size >= 2) {
            unlockBadgeIfEligible(userId, GamificationCatalog.BADGE_POLYGLOT)
        }

        if (!passed) {
            // If this was a real failed test execution (not just missing backend), store in Mistakes Notebook
            if (recordMistakeOnFailure) {
                val existingMistake = progressDao.getMistake(userId, question.id)
                val mistakeToPush = if (existingMistake == null) {
                    MistakeEntity(
                        userId = userId,
                        questionId = question.id,
                        questionTitle = question.title,
                        difficulty = question.difficulty,
                        topic = question.topic,
                        errorReason = statusMessage.ifBlank { "Wrong Answer / Failed Test Case" },
                        failedCodeSnippet = sourceCode.take(800),
                        language = language
                    ).also { progressDao.insertMistake(it) }
                } else {
                    existingMistake.copy(
                        errorReason = statusMessage.ifBlank { existingMistake.errorReason },
                        failedCodeSnippet = sourceCode.take(800),
                        language = language,
                        savedAt = now
                    ).also { progressDao.updateMistake(it) }
                }
                val userEmail = userDao.getUserById(userId)?.email
                if (!userEmail.isNullOrBlank() && supabaseRepository != null && supabaseRepository.isConfigured()) {
                    supabaseRepository.pushMistakeChange(userEmail = userEmail, mistake = mistakeToPush)
                }
            }
            // Strictly enforce rule: wrong or unverified submissions NEVER increase solved count or topic progress.
            return
        }

        // Check if already solved by this user (solving the same question again gives NO additional progress or duplicate XP)
        val alreadySolved = progressDao.getSolvedQuestion(userId, question.id)
        if (alreadySolved != null) {
            return
        }

        // Record unique verified solve
        val rowId = progressDao.insertSolvedQuestion(
            SolvedQuestionEntity(
                userId = userId,
                questionId = question.id,
                topic = question.topic,
                difficulty = question.difficulty,
                solvedAt = now
            )
        )
        if (rowId <= 0L) return

        // Update Topic Progress (+1 up to max 10/10)
        val topicProgress = progressDao.getTopicProgress(userId, question.topic)
        if (topicProgress != null && topicProgress.solvedCount < topicProgress.maxCount) {
            progressDao.updateTopicProgress(
                topicProgress.copy(
                    solvedCount = (topicProgress.solvedCount + 1).coerceAtMost(topicProgress.maxCount)
                )
            )
        }

        // Award base problem XP once per unique questionId
        val problemXp = when (question.difficulty) {
            "Easy" -> 20
            "Medium" -> 35
            "Hard" -> 50
            else -> 20
        }
        awardXpOnce(
            userId = userId,
            actionKey = "SOLVE_QUESTION_${question.id}",
            reason = "Solved ${question.id}: ${question.title} (${question.difficulty})",
            xpDelta = problemXp
        )

        // Check Daily Goals connected to solving Easy / Medium / Hard problems
        when {
            question.difficulty.equals("Easy", ignoreCase = true) -> {
                markDailyGoalCompleted(userId, GOAL_SOLVE_EASY, currentDate, syncToCloud = false)
            }
            question.difficulty.equals("Medium", ignoreCase = true) -> {
                markDailyGoalCompleted(userId, GOAL_SOLVE_MEDIUM, currentDate, syncToCloud = false)
            }
            question.difficulty.equals("Hard", ignoreCase = true) -> {
                markDailyGoalCompleted(userId, GOAL_SOLVE_HARD, currentDate, syncToCloud = false)
            }
        }

        // Check if this question is Today's Daily Challenge
        val allQuestions = questionDao.getAllQuestions().ifEmpty { QuestionBankSeed.INITIAL_QUESTIONS }
        if (allQuestions.isNotEmpty()) {
            val dailyIndex = abs(currentDate.hashCode()) % allQuestions.size
            if (allQuestions[dailyIndex].id == question.id) {
                val dailyMarked = markDailyGoalCompleted(userId, GOAL_DAILY_CHALLENGE, currentDate, syncToCloud = false)
                if (dailyMarked) {
                    createNotificationIfEnabled(
                        userId = userId,
                        uniqueEventKey = "DAILY_CHALLENGE_${currentDate}_${question.id}",
                        category = NOTIF_CAT_DAILY_CHALLENGE,
                        title = "Daily Challenge Completed!",
                        message = "You conquered today's Daily Challenge (${question.title}) and earned +50 XP!"
                    )
                }
                unlockAchievementIfEligible(userId, GamificationCatalog.ACH_FIRST_DAILY_CHALLENGE)
            }
        }

        // Evaluate all Achievements and Badges based on updated real progress
        evaluateAchievementsAndBadges(userId)

        // Automatically synchronize with Supabase cloud when configured
        if (supabaseRepository != null && supabaseRepository.isConfigured()) {
            try {
                supabaseRepository.syncUserData(userId)
            } catch (_: Exception) {}
        }
    }

    /**
     * Marks a specific daily goal completed for the given date, awards its XP once,
     * and checks if the user now qualifies for a daily streak increment (>= 3 daily goals completed).
     */
    suspend fun markDailyGoalCompleted(
        userId: Long,
        goalKey: String,
        currentDate: String = todayDateString(),
        syncToCloud: Boolean = true
    ): Boolean {
        ensureInitializedForUser(userId, currentDate)
        val goal = progressDao.getDailyGoalByKey(userId, currentDate, goalKey) ?: return false
        if (goal.isCompleted) return false

        val updatedGoal = goal.copy(
            isCompleted = true,
            completedAt = System.currentTimeMillis()
        )
        progressDao.updateDailyGoal(updatedGoal)

        awardXpOnce(
            userId = userId,
            actionKey = "DAILY_GOAL_${currentDate}_${goalKey}",
            reason = "Daily Goal: ${goal.title} ($currentDate)",
            xpDelta = goal.xpReward
        )

        createNotificationIfEnabled(
            userId = userId,
            uniqueEventKey = "NOTIF_GOAL_${currentDate}_${goalKey}",
            category = NOTIF_CAT_DAILY_GOAL,
            title = "Daily Goal Completed: ${goal.title}",
            message = "You earned +${goal.xpReward} XP for completing '${goal.title}'."
        )

        unlockBadgeIfEligible(userId, GamificationCatalog.BADGE_CODE_INITIATE)

        // Check streak qualification: a day counts ONLY when the user completes at least 3 daily goals
        val allTodayGoals = progressDao.getDailyGoals(userId, currentDate)
        val completedCount = allTodayGoals.count { it.isCompleted }
        if (completedCount >= 3) {
            evaluateAndIncrementStreakIfEligible(userId, currentDate)
        }

        evaluateAchievementsAndBadges(userId)

        if (syncToCloud && supabaseRepository != null && supabaseRepository.isConfigured()) {
            try {
                supabaseRepository.syncUserData(userId)
            } catch (_: Exception) {}
        }
        return true
    }

    /**
     * Awards XP strictly once per unique (userId, actionKey).
     * Prevents duplicate XP for the same completed question, daily goal, or achievement.
     */
    suspend fun awardXpOnce(
        userId: Long,
        actionKey: String,
        reason: String,
        xpDelta: Int
    ): Boolean {
        if (xpDelta <= 0 || userId <= 0L) return false
        val existing = progressDao.getXpTransaction(userId, actionKey)
        if (existing != null) return false

        val txId = progressDao.insertXpTransaction(
            XpTransactionEntity(
                userId = userId,
                actionKey = actionKey,
                reason = reason,
                xpAmount = xpDelta
            )
        )
        if (txId <= 0L) return false

        val user = userDao.getUserById(userId) ?: return false
        val previousLevel = user.level
        val newXp = user.xp + xpDelta
        val newLevel = LevelProgressionEngine.calculateLevel(newXp)
        val newCoins = user.coins + (xpDelta / 5).coerceAtLeast(1)

        userDao.updateUser(
            user.copy(
                xp = newXp,
                level = newLevel,
                coins = newCoins
            )
        )

        if (newLevel > previousLevel) {
            val rankTitle = LevelProgressionEngine.rankTitleForLevel(newLevel)
            createNotificationIfEnabled(
                userId = userId,
                uniqueEventKey = "LEVEL_UP_${newLevel}",
                category = NOTIF_CAT_LEVEL_UP,
                title = "Level Up! You reached Level $newLevel",
                message = "Congratulations! You are now a Level $newLevel $rankTitle ($newXp total XP)."
            )
        }
        return true
    }

    private suspend fun evaluateAndIncrementStreakIfEligible(userId: Long, currentDate: String) {
        val user = userDao.getUserById(userId) ?: return
        if (user.lastQualifyingDate == currentDate) {
            // Avoid duplicate streak increments on the same day
            return
        }
        val yesterday = previousDateStringFrom(currentDate)
        val nextStreak = if (user.lastQualifyingDate == yesterday) {
            user.currentStreak + 1
        } else {
            1
        }
        val nextLongest = max(user.longestStreak, nextStreak)
        userDao.updateUser(
            user.copy(
                currentStreak = nextStreak,
                longestStreak = nextLongest,
                lastQualifyingDate = currentDate
            )
        )

        createNotificationIfEnabled(
            userId = userId,
            uniqueEventKey = "STREAK_QUALIFIED_${currentDate}_DAY_${nextStreak}",
            category = NOTIF_CAT_STREAK,
            title = "${nextStreak}-Day Coding Streak Active!",
            message = "You completed 3+ Daily Goals on $currentDate. Your coding streak is now $nextStreak day(s)!"
        )
    }

    suspend fun evaluateAchievementsAndBadges(userId: Long) {
        if (userId <= 0L) return
        val solvedList = progressDao.getSolvedQuestions(userId)
        val solvedCount = solvedList.size
        val topics = progressDao.getTopicProgressList(userId)
        val attempts = progressDao.getAllAttempts(userId)

        // Problem Solving Achievements
        if (solvedCount >= 1) unlockAchievementIfEligible(userId, GamificationCatalog.ACH_FIRST_SOLVE)
        if (solvedCount >= 5) unlockAchievementIfEligible(userId, GamificationCatalog.ACH_SOLVE_5)
        if (solvedCount >= 10) unlockAchievementIfEligible(userId, GamificationCatalog.ACH_SOLVE_10)
        if (solvedCount >= 50) unlockAchievementIfEligible(userId, GamificationCatalog.ACH_SOLVE_50)
        if (solvedCount >= 100) unlockAchievementIfEligible(userId, GamificationCatalog.ACH_SOLVE_100)

        // Topic Completion (10/10 in any topic)
        if (topics.any { it.solvedCount >= it.maxCount && it.maxCount > 0 }) {
            unlockAchievementIfEligible(userId, GamificationCatalog.ACH_TOPIC_COMPLETION)
            unlockBadgeIfEligible(userId, GamificationCatalog.BADGE_TOPIC_CHAMPION)
        }

        // High Accuracy (>= 80% across 5+ attempts)
        if (attempts.size >= 5) {
            val accuracyPct = (attempts.count { it.passed } * 100) / attempts.size
            if (accuracyPct >= 80) {
                unlockAchievementIfEligible(userId, GamificationCatalog.ACH_HIGH_ACCURACY)
            }
        }

        // Difficulty Badges
        val easySolved = solvedList.count { it.difficulty.equals("Easy", ignoreCase = true) }
        val mediumSolved = solvedList.count { it.difficulty.equals("Medium", ignoreCase = true) }
        val hardSolved = solvedList.count { it.difficulty.equals("Hard", ignoreCase = true) }

        if (easySolved >= 3) unlockBadgeIfEligible(userId, GamificationCatalog.BADGE_EASY_CONQUEROR)
        if (mediumSolved >= 3) unlockBadgeIfEligible(userId, GamificationCatalog.BADGE_MEDIUM_TACTICIAN)
        if (hardSolved >= 1) unlockBadgeIfEligible(userId, GamificationCatalog.BADGE_HARD_LEGEND)

        // Streak Achievements & Badges
        val userAfterSolves = userDao.getUserById(userId) ?: return
        val maxStreak = max(userAfterSolves.currentStreak, userAfterSolves.longestStreak)
        if (maxStreak >= 3) {
            unlockAchievementIfEligible(userId, GamificationCatalog.ACH_STREAK_3)
            unlockBadgeIfEligible(userId, GamificationCatalog.BADGE_STREAK_GUARDIAN)
        }
        if (maxStreak >= 7) {
            unlockAchievementIfEligible(userId, GamificationCatalog.ACH_STREAK_7)
        }

        // XP Milestone Achievements (re-read user in case achievement XP pushed them over a threshold)
        val latestUser = userDao.getUserById(userId) ?: return
        if (latestUser.xp >= 100) {
            unlockAchievementIfEligible(userId, GamificationCatalog.ACH_XP_100)
        }
        val latestUserAfter100 = userDao.getUserById(userId) ?: return
        if (latestUserAfter100.xp >= 500) {
            unlockAchievementIfEligible(userId, GamificationCatalog.ACH_XP_500)
        }
    }

    private suspend fun unlockAchievementIfEligible(userId: Long, achievementKey: String) {
        val definition = GamificationCatalog.ALL_ACHIEVEMENTS.firstOrNull { it.key == achievementKey }
            ?: return
        val rowId = progressDao.insertUserAchievement(
            com.example.model.UserAchievementEntity(
                userId = userId,
                achievementKey = achievementKey
            )
        )
        if (rowId > 0L) {
            // Newly unlocked! Award bonus XP once and emit notification
            awardXpOnce(
                userId = userId,
                actionKey = "ACHIEVEMENT_REWARD_${achievementKey}",
                reason = "Achievement Unlocked: ${definition.title}",
                xpDelta = definition.xpReward
            )
            createNotificationIfEnabled(
                userId = userId,
                uniqueEventKey = "NOTIF_ACH_${achievementKey}",
                category = NOTIF_CAT_ACHIEVEMENT,
                title = "Achievement Unlocked: ${definition.title}",
                message = "${definition.description} (+${definition.xpReward} XP)"
            )
        }
    }

    private suspend fun unlockBadgeIfEligible(userId: Long, badgeKey: String) {
        val definition = GamificationCatalog.ALL_BADGES.firstOrNull { it.key == badgeKey } ?: return
        val rowId = progressDao.insertUserBadge(
            com.example.model.UserBadgeEntity(
                userId = userId,
                badgeKey = badgeKey
            )
        )
        if (rowId > 0L) {
            createNotificationIfEnabled(
                userId = userId,
                uniqueEventKey = "NOTIF_BADGE_${badgeKey}",
                category = NOTIF_CAT_BADGE,
                title = "Badge Earned: ${definition.title}",
                message = "${definition.subtitle} — ${definition.requirementText}"
            )
        }
    }

    private suspend fun createNotificationIfEnabled(
        userId: Long,
        uniqueEventKey: String,
        category: String,
        title: String,
        message: String
    ) {
        val prefs = sessionPreferences?.getNotificationPreferences()
        val allowed = when (category) {
            NOTIF_CAT_ACHIEVEMENT, NOTIF_CAT_BADGE, NOTIF_CAT_LEVEL_UP ->
                prefs?.achievementUnlockedAlert ?: true
            NOTIF_CAT_STREAK ->
                prefs?.streakReminder ?: true
            NOTIF_CAT_DAILY_CHALLENGE ->
                prefs?.dailyChallengeAlert ?: true
            NOTIF_CAT_DAILY_GOAL ->
                prefs?.learningGoalsAlert ?: true
            else -> true
        }
        if (!allowed) return

        val entity = AppNotificationEntity(
            userId = userId,
            uniqueEventKey = uniqueEventKey,
            category = category,
            title = title,
            message = message
        )
        val rowId = progressDao.insertNotification(entity)
        if (rowId > 0L) {
            try {
                onNotificationCreated?.invoke(entity.copy(id = rowId))
            } catch (_: Exception) {
            }
        }
    }

    private fun previousDateStringFrom(dateStr: String): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val parsed = sdf.parse(dateStr) ?: return yesterdayDateString()
            val cal = Calendar.getInstance()
            cal.time = parsed
            cal.add(Calendar.DAY_OF_YEAR, -1)
            sdf.format(cal.time)
        } catch (_: Exception) {
            yesterdayDateString()
        }
    }
}
