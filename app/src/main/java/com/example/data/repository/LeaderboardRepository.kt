package com.example.data.repository

import com.example.data.local.UserDao
import com.example.data.remote.CodeQuestProductionApiService
import com.example.data.remote.LeaderboardEntryDto
import com.example.data.remote.LeaderboardResponse
import com.example.utils.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

sealed class LeaderboardResult {
    data class Success(val response: LeaderboardResponse) : LeaderboardResult()
    data class OfflineCache(val response: LeaderboardResponse, val message: String) : LeaderboardResult()
    data class Error(val message: String) : LeaderboardResult()
}

/**
 * Server-authoritative Leaderboard Repository.
 * Production rule: Client-submitted XP is NEVER trusted blindly for global ranking.
 * Server computes authoritative rankings via GET /leaderboard.
 * Local user profile is kept purely as offline display fallback when network is unavailable.
 */
class LeaderboardRepository(
    private val userDao: UserDao,
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

    suspend fun getLeaderboard(period: String = "weekly", currentUserId: Long): LeaderboardResult = withContext(Dispatchers.IO) {
        val localUser = userDao.getUserById(currentUserId)

        // 1. If Supabase is configured, fetch real cloud leaderboard
        if (supabaseRepository != null && supabaseRepository.isConfigured()) {
            try {
                val supabaseEntries = supabaseRepository.getGlobalLeaderboard(limit = 50)
                if (supabaseEntries.isNotEmpty()) {
                    val userEmail = localUser?.email.orEmpty()
                    val userRank = supabaseEntries.indexOfFirst { it.userId == userEmail }.let { if (it >= 0) it + 1 else 1 }
                    return@withContext LeaderboardResult.Success(
                        LeaderboardResponse(
                            period = period,
                            userRank = userRank,
                            entries = supabaseEntries
                        )
                    )
                }
            } catch (_: Exception) {
                // Fallback below
            }
        }

        // 2. If custom backend is configured, fetch authoritative ranking from server
        if (AppConfig.isBackendConfigured() && apiService != null) {
            val token = tokenProvider() ?: "Bearer guest"
            try {
                val serverResponse = apiService!!.getLeaderboard(token, period)
                return@withContext LeaderboardResult.Success(serverResponse)
            } catch (e: Exception) {
                // Network failure: fall back to offline cache below
            }
        }

        // 3. Offline / local fallback cached from local Room user
        val entries = mutableListOf<LeaderboardEntryDto>()

        if (localUser != null) {
            entries.add(
                LeaderboardEntryDto(
                    rank = 1,
                    userId = localUser.id.toString(),
                    displayName = localUser.fullName,
                    avatarKey = localUser.avatarKey,
                    level = localUser.level,
                    totalXp = localUser.xp,
                    streak = localUser.currentStreak,
                    solvedCount = 0
                )
            )
        }

        LeaderboardResult.OfflineCache(
            response = LeaderboardResponse(
                period = period,
                userRank = if (localUser != null) 1 else 0,
                entries = entries
            ),
            message = "Displaying locally cached rank. Global rankings appear automatically when connected."
        )
    }
}
