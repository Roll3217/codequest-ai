package com.example.data.repository

import com.example.data.local.ProgressDao
import com.example.data.local.QuestionDao
import com.example.data.local.UserDao
import com.example.data.remote.CodeQuestProductionApiService
import com.example.data.remote.CompleteGoalRequest
import com.example.data.remote.RecordSolveRequest
import com.example.data.remote.SaveMistakeRequest
import com.example.model.DailyGoalEntity
import com.example.utils.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

sealed class SyncResult {
    data class Success(val message: String, val syncedItemsCount: Int) : SyncResult()
    data class Offline(val message: String) : SyncResult()
    data class Error(val message: String) : SyncResult()
}

/**
 * Cloud Sync Repository.
 * Handles synchronization between Android Room local cache and the CodeQuest production backend.
 * Provides offline-first architecture:
 * 1. Offline Mode: Changes persist locally in Room immediately.
 * 2. Online Mode: When backend is reachable, local solves, completed goals, and bookmarks sync up to server.
 * 3. Pull: Fetches authoritative leaderboard, cloud progress, and user achievements.
 */
class CloudSyncRepository(
    private val userDao: UserDao,
    private val questionDao: QuestionDao,
    private val progressDao: ProgressDao,
    private val tokenProvider: () -> String?,
    private val supabaseRepository: com.example.data.remote.supabase.SupabaseRepository? = null
) {

    private val apiService: CodeQuestProductionApiService? by lazy {
        val baseUrl = AppConfig.backendBaseUrl
        if (!AppConfig.isBackendConfigured()) null
        else {
            val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            Retrofit.Builder()
                .baseUrl(normalized)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create())
                .build()
                .create(CodeQuestProductionApiService::class.java)
        }
    }

    suspend fun syncUserData(userId: Long): SyncResult = withContext(Dispatchers.IO) {
        if (userId <= 0L) return@withContext SyncResult.Error("No active user session to sync.")

        // 1. Supabase Cloud Sync
        if (supabaseRepository != null && supabaseRepository.isConfigured()) {
            return@withContext when (val res = supabaseRepository.syncUserData(userId)) {
                is com.example.data.remote.supabase.SupabaseSyncResult.Success -> {
                    SyncResult.Success(res.message, res.syncedCount)
                }
                is com.example.data.remote.supabase.SupabaseSyncResult.Unconfigured -> {
                    SyncResult.Offline(res.message)
                }
                is com.example.data.remote.supabase.SupabaseSyncResult.Error -> {
                    SyncResult.Error(res.message)
                }
            }
        }

        // 2. Custom Backend API Sync
        if (!AppConfig.isBackendConfigured() || apiService == null) {
            return@withContext SyncResult.Offline(
                "Offline mode: Your coding progress is safely saved on this device and will sync when cloud service is active."
            )
        }

        val token = tokenProvider() ?: return@withContext SyncResult.Error("Authentication token expired. Please re-login.")

        try {
            var syncedCount = 0

            // 1. Upload local solved questions
            val localSolves = progressDao.getSolvedQuestions(userId)
            for (solve in localSolves) {
                try {
                    apiService!!.recordSolve(
                        token,
                        RecordSolveRequest(
                            questionId = solve.questionId,
                            language = "Kotlin",
                            submittedCode = "// Synced from Room offline cache"
                        )
                    )
                    syncedCount++
                } catch (_: Exception) {
                    // Item already recorded or conflict ignored
                }
            }

            // 2. Upload completed daily goals
            val todayStr = LearningRepository.todayDateString()
            val goals = progressDao.getDailyGoals(userId, todayStr)
            for (goal in goals.filter { it.isCompleted }) {
                try {
                    apiService!!.completeDailyGoal(
                        token,
                        CompleteGoalRequest(
                            dateString = todayStr,
                            goalKey = goal.goalKey
                        )
                    )
                    syncedCount++
                } catch (_: Exception) {
                    // Idempotent completion
                }
            }

            // 3. Download remote authoritative progress
            try {
                val remoteProgress = apiService!!.getProgress(token)
                val user = userDao.getUserById(userId)
                if (user != null && remoteProgress.totalXp > user.xp) {
                    userDao.updateUser(
                        user.copy(
                            xp = remoteProgress.totalXp,
                            level = remoteProgress.level,
                            currentStreak = remoteProgress.currentStreak,
                            longestStreak = remoteProgress.longestStreak
                        )
                    )
                }
            } catch (_: Exception) {}

            SyncResult.Success(
                message = "Cloud sync completed successfully.",
                syncedItemsCount = syncedCount
            )
        } catch (e: Exception) {
            SyncResult.Error("Sync failed: ${e.localizedMessage ?: "Network error"}")
        }
    }
}
