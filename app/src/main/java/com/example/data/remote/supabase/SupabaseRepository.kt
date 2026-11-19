package com.example.data.remote.supabase

import com.example.data.local.ProgressDao
import com.example.data.local.QuestionDao
import com.example.data.local.SessionPreferencesManager
import com.example.data.local.UserDao
import com.example.data.remote.LeaderboardEntryDto
import com.example.data.repository.LearningRepository
import com.example.model.BookmarkEntity
import com.example.model.DailyGoalEntity
import com.example.model.MistakeEntity
import com.example.model.SolvedQuestionEntity
import com.example.model.TopicProgressEntity
import com.example.model.UserAchievementEntity
import com.example.model.UserBadgeEntity
import com.example.model.UserEntity
import com.example.model.XpTransactionEntity
import com.example.ui.achievements.GamificationCatalog
import com.example.ui.achievements.LevelProgressionEngine
import com.example.utils.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max

sealed class SupabaseSyncResult {
    data class Success(val message: String, val syncedCount: Int) : SupabaseSyncResult()
    data class Unconfigured(val message: String) : SupabaseSyncResult()
    data class Error(val message: String) : SupabaseSyncResult()
}

class SupabaseRepository(
    private val client: SupabaseClient = SupabaseClient(),
    private val userDao: UserDao,
    private val questionDao: QuestionDao,
    private val progressDao: ProgressDao,
    private val sessionPrefs: SessionPreferencesManager
) {

    fun isConfigured(): Boolean = client.isClientConfigured()

    fun getEndpointDisplay(): String = AppConfig.supabaseHost

    suspend fun ping(): Boolean = client.ping()

    suspend fun pingDiagnostic(): SupabaseDiagnostic = client.pingDiagnostic()

    // ==========================================
    // SUPABASE AUTHENTICATION & REGISTRATION
    // ==========================================

    suspend fun signUpWithSupabase(
        email: String,
        password: String,
        fullName: String,
        avatarKey: String,
        isCustomPhoto: Boolean = false,
        interests: String,
        preferredLanguage: String,
        passwordHash: String = ""
    ): Result<SupabaseAuthResponse> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured."))
        }

        val normalizedEmail = email.trim().lowercase(Locale.ROOT)

        // 1. Verify public.profiles is reachable BEFORE calling GoTrue signup so we never create orphan auth.users rows
        val existingProfileRes = client.getProfile(normalizedEmail)
        if (existingProfileRes.isFailure) {
            val profileErr = existingProfileRes.exceptionOrNull()?.localizedMessage.orEmpty()
            return@withContext when {
                profileErr.contains("supabase_schema.sql", ignoreCase = true) ||
                    profileErr.contains("PGRST205", ignoreCase = true) ||
                    profileErr.contains("Could not find the table", ignoreCase = true) ||
                    profileErr.contains("does not exist", ignoreCase = true) ||
                    profileErr.contains("404", ignoreCase = true) ->
                    Result.failure(
                        Exception("Supabase 'profiles' table is missing. Open Supabase SQL Editor and run 'supabase_schema.sql'.")
                    )
                profileErr.contains("401", ignoreCase = true) ||
                    profileErr.contains("403", ignoreCase = true) ||
                    profileErr.contains("42501", ignoreCase = true) ||
                    profileErr.contains("JWT", ignoreCase = true) ->
                    Result.failure(
                        Exception("Supabase rejected the request (API key or RLS policy). Check SUPABASE_ANON_KEY and run 'supabase_schema.sql'.")
                    )
                else -> Result.failure(
                    existingProfileRes.exceptionOrNull()
                        ?: Exception("Could not connect to Supabase (${AppConfig.supabaseHost}). Check your internet connection.")
                )
            }
        }
        val existingProfile = existingProfileRes.getOrNull()
        val hasExistingProfile = existingProfile != null

        // 2. Register in Supabase Auth (GoTrue)
        val authResult = client.signUp(
            email = normalizedEmail,
            password = password,
            fullName = fullName,
            avatarKey = avatarKey,
            interests = interests,
            preferredLanguage = preferredLanguage
        )

        var session = authResult.getOrNull()
        if (authResult.isFailure) {
            val errMsg = authResult.exceptionOrNull()?.localizedMessage.orEmpty()
            val isDuplicateAuth = errMsg.contains("already registered", ignoreCase = true) ||
                errMsg.contains("already exists", ignoreCase = true)
            val isCooldownError = errMsg.contains("security purposes", ignoreCase = true) ||
                errMsg.contains("only request this after", ignoreCase = true) ||
                errMsg.contains("too many requests", ignoreCase = true) ||
                (errMsg.contains("429", ignoreCase = true) && !errMsg.contains("over_email_send_rate_limit", ignoreCase = true))
            val isSoftAuthError = errMsg.contains("rate limit", ignoreCase = true) ||
                errMsg.contains("over_email_send_rate_limit", ignoreCase = true) ||
                errMsg.contains("Error sending", ignoreCase = true) ||
                errMsg.contains("confirmation email", ignoreCase = true) ||
                errMsg.contains("Database error saving", ignoreCase = true) ||
                errMsg.contains("email_provider_disabled", ignoreCase = true) ||
                errMsg.contains("Signups not allowed", ignoreCase = true) ||
                errMsg.contains("Signup is disabled", ignoreCase = true)

            if (hasExistingProfile) {
                return@withContext when {
                    isCooldownError -> Result.failure(
                        Exception("Too many signup attempts right now. Please wait ~60 seconds and try again.")
                    )
                    isDuplicateAuth || isSoftAuthError -> Result.failure(
                        Exception("An account with this email already exists on Supabase. Please sign in.")
                    )
                    else -> Result.failure(
                        authResult.exceptionOrNull() ?: Exception("Supabase registration failed.")
                    )
                }
            }

            when {
                isDuplicateAuth -> {
                    // User existed in auth.users (possibly from an earlier incomplete/unconfirmed signup with no public.profiles row)
                    val fallbackLogin = client.login(normalizedEmail, password)
                    if (fallbackLogin.isSuccess) {
                        session = fallbackLogin.getOrNull()
                    } else {
                        val loginErr = fallbackLogin.exceptionOrNull()?.localizedMessage.orEmpty()
                        if (loginErr.contains("Email not confirmed", ignoreCase = true) ||
                            loginErr.contains("not confirmed", ignoreCase = true)
                        ) {
                            // Unconfirmed orphan stub in auth.users with no public.profiles row -> complete registration in public.profiles
                            session = SupabaseAuthResponse(
                                accessToken = null,
                                refreshToken = null,
                                user = SupabaseUser(id = "", email = normalizedEmail)
                            )
                        } else {
                            return@withContext Result.failure(
                                Exception("An account with this email already exists on Supabase. Please sign in.")
                            )
                        }
                    }
                }
                isCooldownError -> {
                    return@withContext Result.failure(
                        Exception("Too many signup attempts right now. Please wait ~60 seconds and try again.")
                    )
                }
                isSoftAuthError -> {
                    // Supabase SMTP failure or disabled email provider:
                    // still register the account in public.profiles with passwordHash for cross-device login
                    session = SupabaseAuthResponse(
                        accessToken = null,
                        refreshToken = null,
                        user = SupabaseUser(id = "", email = normalizedEmail)
                    )
                }
                else -> {
                    return@withContext Result.failure(
                        authResult.exceptionOrNull() ?: Exception("Supabase registration failed.")
                    )
                }
            }
        }

        session?.accessToken?.takeIf { it.isNotBlank() }?.let { token ->
            sessionPrefs.supabaseAccessToken = token
        }
        session?.refreshToken?.takeIf { it.isNotBlank() }?.let { token ->
            sessionPrefs.supabaseRefreshToken = token
        }

        // 3. ALWAYS insert/upsert the profile into public.profiles and fail loudly if table/RLS rejects it
        val safeAvatarKey = if (isCustomPhoto) "avatar_cyber" else avatarKey.ifBlank { "avatar_cyber" }
        val profileResult = client.upsertProfile(
            SupabaseProfile(
                id = session?.user?.id?.ifBlank { null },
                email = normalizedEmail,
                fullName = fullName,
                avatarKey = avatarKey.ifBlank { safeAvatarKey },
                isCustomPhoto = isCustomPhoto,
                interests = interests,
                preferredLanguage = preferredLanguage,
                xp = 0,
                level = 1,
                coins = 50,
                currentStreak = 0,
                longestStreak = 0,
                lastQualifyingDate = null,
                passwordHash = passwordHash
            ),
            accessToken = session?.accessToken
        )

        if (profileResult.isFailure) {
            return@withContext Result.failure(
                profileResult.exceptionOrNull()
                    ?: Exception("Failed to register user inside Supabase 'profiles' table.")
            )
        }

        Result.success(
            session ?: SupabaseAuthResponse(
                user = SupabaseUser(id = "", email = normalizedEmail)
            )
        )
    }

    suspend fun loginWithSupabase(
        email: String,
        password: String,
        passwordHash: String = ""
    ): Result<SupabaseAuthResponse> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured."))
        }

        val normalizedEmail = email.trim().lowercase(Locale.ROOT)
        val loginResult = client.login(normalizedEmail, password)

        if (loginResult.isSuccess) {
            val session = loginResult.getOrNull()!!
            session.accessToken?.takeIf { it.isNotBlank() }?.let { token ->
                sessionPrefs.supabaseAccessToken = token
            }
            session.refreshToken?.takeIf { it.isNotBlank() }?.let { token ->
                sessionPrefs.supabaseRefreshToken = token
            }

            // Guarantee a row exists in public.profiles (and backfill password_hash if missing)
            val existingProfileRes = client.getProfile(normalizedEmail, session.accessToken)
            if (existingProfileRes.isFailure) {
                val err = existingProfileRes.exceptionOrNull()?.localizedMessage.orEmpty()
                if (err.contains("supabase_schema.sql", ignoreCase = true) ||
                    err.contains("PGRST205", ignoreCase = true) ||
                    err.contains("Could not find the table", ignoreCase = true) ||
                    err.contains("does not exist", ignoreCase = true) ||
                    err.contains("404", ignoreCase = true)
                ) {
                    return@withContext Result.failure(
                        Exception("Supabase 'profiles' table is missing. Open Supabase SQL Editor and run 'supabase_schema.sql'.")
                    )
                }
            }
            val existingProfile = existingProfileRes.getOrNull()
            if (existingProfile == null) {
                val fullName = session.user?.email?.substringBefore("@")
                    ?.replaceFirstChar { it.uppercase() } ?: "Coder"
                val upsertRes = client.upsertProfile(
                    SupabaseProfile(
                        id = session.user?.id?.ifBlank { null },
                        email = normalizedEmail,
                        fullName = fullName,
                        passwordHash = passwordHash
                    ),
                    accessToken = session.accessToken
                )
                if (upsertRes.isFailure) {
                    return@withContext Result.failure(
                        upsertRes.exceptionOrNull() ?: Exception("Failed to register profile row in Supabase.")
                    )
                }
            } else if (existingProfile.passwordHash.isBlank() && passwordHash.isNotBlank()) {
                client.upsertProfile(
                    existingProfile.copy(passwordHash = passwordHash),
                    accessToken = session.accessToken
                )
            }
            return@withContext loginResult
        }

        // Fallback if Auth rejected due to Confirm Email pending OR user migrated directly into public.profiles
        val errMsg = loginResult.exceptionOrNull()?.localizedMessage.orEmpty()
        val remoteProfileRes = client.getProfile(normalizedEmail)
        if (remoteProfileRes.isFailure) {
            val profileErr = remoteProfileRes.exceptionOrNull()?.localizedMessage.orEmpty()
            return@withContext when {
                profileErr.contains("supabase_schema.sql", ignoreCase = true) ||
                    profileErr.contains("PGRST205", ignoreCase = true) ||
                    profileErr.contains("Could not find the table", ignoreCase = true) ||
                    profileErr.contains("does not exist", ignoreCase = true) ||
                    profileErr.contains("404", ignoreCase = true) ->
                    Result.failure(
                        Exception("Supabase 'profiles' table is missing. Open Supabase SQL Editor and run 'supabase_schema.sql'.")
                    )
                profileErr.contains("401", ignoreCase = true) ||
                    profileErr.contains("403", ignoreCase = true) ||
                    profileErr.contains("42501", ignoreCase = true) ->
                    Result.failure(
                        Exception("Supabase rejected the request (API key or RLS policy). Check SUPABASE_ANON_KEY and run 'supabase_schema.sql'.")
                    )
                else -> loginResult
            }
        }

        val remoteProfile = remoteProfileRes.getOrNull()
        if (remoteProfile != null) {
            if (remoteProfile.passwordHash.isNotBlank()) {
                return@withContext if (remoteProfile.passwordHash == passwordHash) {
                    var fallbackSession = SupabaseAuthResponse(
                        accessToken = null,
                        refreshToken = null,
                        user = SupabaseUser(
                            id = remoteProfile.id ?: normalizedEmail,
                            email = normalizedEmail
                        )
                    )
                    if (!errMsg.contains("Email not confirmed", ignoreCase = true)) {
                        runCatching {
                            val promoted = client.signUp(
                                email = normalizedEmail,
                                password = password,
                                fullName = remoteProfile.fullName,
                                avatarKey = remoteProfile.avatarKey,
                                interests = remoteProfile.interests,
                                preferredLanguage = remoteProfile.preferredLanguage
                            ).getOrNull()
                            if (promoted != null) {
                                promoted.accessToken?.takeIf { it.isNotBlank() }?.let { sessionPrefs.supabaseAccessToken = it }
                                promoted.refreshToken?.takeIf { it.isNotBlank() }?.let { sessionPrefs.supabaseRefreshToken = it }
                                fallbackSession = promoted
                            }
                        }
                    }
                    Result.success(fallbackSession)
                } else {
                    Result.failure(Exception("Incorrect password. Please check your credentials and try again."))
                }
            }
            if (errMsg.contains("Email not confirmed", ignoreCase = true)) {
                return@withContext Result.failure(
                    Exception("Please confirm your email link from Gmail, or re-run 'supabase_schema.sql' and disable 'Confirm email' in Supabase Auth settings.")
                )
            }
            return@withContext Result.failure(
                Exception("Incorrect password. Please check your credentials and try again.")
            )
        }

        if (errMsg.contains("Email not confirmed", ignoreCase = true)) {
            return@withContext Result.failure(
                Exception("Your email is unconfirmed in Supabase Auth and has no profile row. Please sign up again or disable 'Confirm email' in Supabase Auth settings.")
            )
        }

        loginResult
    }

    /**
     * Ensures a locally existing account (e.g. created offline or before Supabase URL fix)
     * is registered in Supabase Auth (best-effort) and public.profiles so it works on all devices.
     */
    suspend fun ensureUserRegisteredInCloud(user: UserEntity, rawPassword: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.success(Unit)
        val normalizedEmail = user.email.trim().lowercase(Locale.ROOT)
        var token: String? = sessionPrefs.supabaseAccessToken

        if (!rawPassword.isNullOrBlank()) {
            val loginRes = client.login(normalizedEmail, rawPassword)
            if (loginRes.isSuccess) {
                token = loginRes.getOrNull()?.accessToken
            } else {
                val signUpRes = client.signUp(
                    email = normalizedEmail,
                    password = rawPassword,
                    fullName = user.fullName,
                    avatarKey = user.avatarKey,
                    interests = user.interests,
                    preferredLanguage = user.preferredLanguage
                )
                if (signUpRes.isSuccess) {
                    token = signUpRes.getOrNull()?.accessToken
                }
            }
            if (!token.isNullOrBlank()) {
                sessionPrefs.supabaseAccessToken = token
            }
        }

        client.upsertProfile(
            SupabaseProfile(
                email = normalizedEmail,
                fullName = user.fullName,
                avatarKey = user.avatarKey,
                isCustomPhoto = user.isCustomPhoto,
                interests = user.interests,
                preferredLanguage = user.preferredLanguage,
                xp = user.xp,
                level = user.level,
                coins = user.coins,
                currentStreak = user.currentStreak,
                longestStreak = user.longestStreak,
                lastQualifyingDate = user.lastQualifyingDate,
                passwordHash = user.passwordHash
            ),
            accessToken = token
        )
    }

    suspend fun logout(tokenOverride: String? = null): Boolean = withContext(Dispatchers.IO) {
        val token = tokenOverride?.takeIf { it.isNotBlank() }
            ?: sessionPrefs.supabaseAccessToken.orEmpty()
        val success = if (token.isNotBlank()) client.logout(token) else true
        sessionPrefs.supabaseAccessToken = null
        sessionPrefs.supabaseRefreshToken = null
        success
    }

    suspend fun getProfile(email: String, accessToken: String? = null): SupabaseProfile? = withContext(Dispatchers.IO) {
        if (!isConfigured()) null
        else client.getProfile(email, accessToken).getOrNull()
    }

    suspend fun syncProfileOnly(user: UserEntity) = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext
        try {
            val existingRemote = client.getProfile(user.email, sessionPrefs.supabaseAccessToken).getOrNull()
            val mergedXp = max(user.xp, existingRemote?.xp ?: 0)
            val mergedLevel = max(
                LevelProgressionEngine.calculateLevel(mergedXp),
                max(user.level, existingRemote?.level ?: 1)
            )
            client.upsertProfile(
                SupabaseProfile(
                    id = existingRemote?.id,
                    email = user.email,
                    fullName = user.fullName,
                    avatarKey = user.avatarKey,
                    isCustomPhoto = user.isCustomPhoto,
                    interests = user.interests,
                    preferredLanguage = user.preferredLanguage,
                    xp = mergedXp,
                    level = mergedLevel,
                    coins = max(user.coins, existingRemote?.coins ?: 50),
                    currentStreak = max(user.currentStreak, existingRemote?.currentStreak ?: 0),
                    longestStreak = max(user.longestStreak, existingRemote?.longestStreak ?: 0),
                    lastQualifyingDate = user.lastQualifyingDate ?: existingRemote?.lastQualifyingDate,
                    passwordHash = user.passwordHash.ifBlank { existingRemote?.passwordHash.orEmpty() }
                ),
                accessToken = sessionPrefs.supabaseAccessToken
            )
        } catch (_: Exception) {}
    }

    suspend fun pushBookmarkChange(
        userEmail: String,
        bookmark: BookmarkEntity? = null,
        removedQuestionId: String? = null
    ) = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext
        try {
            val token = sessionPrefs.supabaseAccessToken
            if (bookmark != null) {
                client.upsertBookmarks(
                    listOf(
                        SupabaseBookmark(
                            userEmail = userEmail,
                            questionId = bookmark.questionId,
                            questionTitle = bookmark.questionTitle,
                            difficulty = bookmark.difficulty,
                            topic = bookmark.topic,
                            savedAt = bookmark.savedAt
                        )
                    ),
                    token
                )
            } else if (!removedQuestionId.isNullOrBlank()) {
                client.deleteBookmark(userEmail, removedQuestionId, token)
            }
        } catch (_: Exception) {}
    }

    suspend fun pushMistakeChange(
        userEmail: String,
        mistake: MistakeEntity? = null,
        removedQuestionId: String? = null
    ) = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext
        try {
            val token = sessionPrefs.supabaseAccessToken
            if (mistake != null) {
                client.upsertMistakes(
                    listOf(
                        SupabaseMistake(
                            userEmail = userEmail,
                            questionId = mistake.questionId,
                            questionTitle = mistake.questionTitle,
                            difficulty = mistake.difficulty,
                            topic = mistake.topic,
                            errorReason = mistake.errorReason,
                            failedCodeSnippet = mistake.failedCodeSnippet,
                            language = mistake.language,
                            userNotes = mistake.userNotes,
                            savedAt = mistake.savedAt
                        )
                    ),
                    token
                )
            } else if (!removedQuestionId.isNullOrBlank()) {
                client.deleteMistake(userEmail, removedQuestionId, token)
            }
        } catch (_: Exception) {}
    }

    // ==========================================
    // FULL CROSS-DEVICE CLOUD SYNCHRONIZATION
    // Order: PULL -> MERGE -> RECALCULATE -> PUSH
    // ==========================================

    suspend fun syncUserData(userId: Long, preferRemote: Boolean = false): SupabaseSyncResult = withContext(Dispatchers.IO) {
        if (userId <= 0L) {
            return@withContext SupabaseSyncResult.Error("No active user session to sync.")
        }

        if (!isConfigured()) {
            return@withContext SupabaseSyncResult.Unconfigured(
                "Offline Local Mode: Your coding progress is safely persisted on this device."
            )
        }

        var localUser = userDao.getUserById(userId)
            ?: return@withContext SupabaseSyncResult.Error("User record not found in local database.")

        val accessToken = sessionPrefs.supabaseAccessToken
        val todayDate = LearningRepository.todayDateString()
        var itemsSynced = 0

        try {
            ensureTopicsAndTodayGoalsExist(userId, todayDate)

            // 1. PULL remote profile FIRST so fresh logins never overwrite cloud stats with 0 XP
            val remoteProfileRes = client.getProfile(localUser.email, accessToken)
            if (remoteProfileRes.isFailure) {
                return@withContext SupabaseSyncResult.Error(
                    "Supabase read failed: ${remoteProfileRes.exceptionOrNull()?.localizedMessage ?: "Check supabase_schema.sql"}"
                )
            }
            val remoteProfile = remoteProfileRes.getOrNull()

            if (remoteProfile != null) {
                val mergedXp = max(localUser.xp, remoteProfile.xp)
                val mergedLevel = max(
                    LevelProgressionEngine.calculateLevel(mergedXp),
                    max(localUser.level, remoteProfile.level)
                )
                val mergedCoins = max(localUser.coins, remoteProfile.coins)
                val mergedLongestStreak = max(localUser.longestStreak, remoteProfile.longestStreak)
                val mergedLastQualifyingDate = listOfNotNull(
                    localUser.lastQualifyingDate?.takeIf { it.isNotBlank() },
                    remoteProfile.lastQualifyingDate?.takeIf { it.isNotBlank() }
                ).maxOrNull()

                val mergedCurrentStreak = if (preferRemote || remoteProfile.xp > localUser.xp) {
                    remoteProfile.currentStreak
                } else {
                    max(localUser.currentStreak, remoteProfile.currentStreak)
                }

                // Resolve avatar safely across devices (custom file path won't exist on a different device)
                val resolvedAvatarKey: String
                val resolvedIsCustomPhoto: Boolean
                if (remoteProfile.isCustomPhoto) {
                    if (File(remoteProfile.avatarKey).exists()) {
                        resolvedAvatarKey = remoteProfile.avatarKey
                        resolvedIsCustomPhoto = true
                    } else if (localUser.isCustomPhoto && File(localUser.avatarKey).exists()) {
                        resolvedAvatarKey = localUser.avatarKey
                        resolvedIsCustomPhoto = true
                    } else {
                        resolvedAvatarKey = "avatar_cyber"
                        resolvedIsCustomPhoto = false
                    }
                } else {
                    resolvedAvatarKey = remoteProfile.avatarKey.ifBlank { localUser.avatarKey }
                    resolvedIsCustomPhoto = false
                }

                val useRemoteMetadata = preferRemote || (localUser.xp == 0 && remoteProfile.xp > 0)
                val mergedUser = localUser.copy(
                    fullName = if (useRemoteMetadata && remoteProfile.fullName.isNotBlank()) remoteProfile.fullName else localUser.fullName,
                    avatarKey = if (useRemoteMetadata) resolvedAvatarKey else localUser.avatarKey,
                    isCustomPhoto = if (useRemoteMetadata) resolvedIsCustomPhoto else localUser.isCustomPhoto,
                    interests = if (useRemoteMetadata && remoteProfile.interests.isNotBlank()) remoteProfile.interests else localUser.interests,
                    preferredLanguage = if (useRemoteMetadata && remoteProfile.preferredLanguage.isNotBlank()) remoteProfile.preferredLanguage else localUser.preferredLanguage,
                    xp = mergedXp,
                    level = mergedLevel,
                    coins = mergedCoins,
                    currentStreak = mergedCurrentStreak,
                    longestStreak = mergedLongestStreak,
                    lastQualifyingDate = mergedLastQualifyingDate,
                    passwordHash = localUser.passwordHash.ifBlank { remoteProfile.passwordHash }
                )

                if (mergedUser != localUser) {
                    userDao.updateUser(mergedUser)
                    localUser = mergedUser
                    itemsSynced++
                }
            }

            // 2. PULL remote Solved Questions -> Merge into Room -> Backfill XP transactions (prevents duplicate XP)
            val remoteSolves = client.fetchUserProgress(localUser.email, accessToken).getOrNull().orEmpty()
            val localSolvesBefore = progressDao.getSolvedQuestions(userId)
            val localSolveIds = localSolvesBefore.map { it.questionId }.toMutableSet()

            for (remote in remoteSolves) {
                if (remote.questionId.isBlank()) continue
                if (!localSolveIds.contains(remote.questionId)) {
                    progressDao.insertSolvedQuestion(
                        SolvedQuestionEntity(
                            userId = userId,
                            questionId = remote.questionId,
                            topic = remote.topic,
                            difficulty = remote.difficulty,
                            solvedAt = remote.solvedAt
                        )
                    )
                    val xpForSolve = when (remote.difficulty) {
                        "Medium" -> 35
                        "Hard" -> 50
                        else -> 20
                    }
                    progressDao.insertXpTransaction(
                        XpTransactionEntity(
                            userId = userId,
                            actionKey = "SOLVE_QUESTION_${remote.questionId}",
                            reason = "Synced solved ${remote.questionId}",
                            xpAmount = xpForSolve,
                            createdAt = remote.solvedAt
                        )
                    )
                    localSolveIds.add(remote.questionId)
                    itemsSynced++
                }
            }

            // 3. RECALCULATE all 8 Topic Learning Paths from merged solved_questions
            val allSolvedNow = progressDao.getSolvedQuestions(userId)
            val solvedCountByTopic = allSolvedNow.groupingBy { it.topic.lowercase(Locale.ROOT) }.eachCount()
            val topicList = progressDao.getTopicProgressList(userId)
            for (tp in topicList) {
                val expectedCount = (solvedCountByTopic[tp.topicName.lowercase(Locale.ROOT)] ?: 0)
                    .coerceIn(0, tp.maxCount)
                if (tp.solvedCount != expectedCount) {
                    progressDao.updateTopicProgress(tp.copy(solvedCount = expectedCount))
                }
            }

            // 4. PULL remote Daily Goals -> Merge into Room + Backfill XP transactions
            val remoteGoals = client.fetchDailyGoals(localUser.email, null, accessToken).getOrNull().orEmpty()
            for (rg in remoteGoals) {
                if (rg.dateString.isBlank() || rg.goalKey.isBlank()) continue
                val existingGoal = progressDao.getDailyGoalByKey(userId, rg.dateString, rg.goalKey)
                if (existingGoal == null) {
                    progressDao.insertDailyGoals(
                        listOf(
                            DailyGoalEntity(
                                userId = userId,
                                dateString = rg.dateString,
                                goalKey = rg.goalKey,
                                title = rg.title.ifBlank { rg.goalKey },
                                xpReward = rg.xpReward,
                                isCompleted = rg.isCompleted,
                                completedAt = rg.completedAt
                            )
                        )
                    )
                    if (rg.isCompleted) itemsSynced++
                } else if (rg.isCompleted && !existingGoal.isCompleted) {
                    progressDao.updateDailyGoal(
                        existingGoal.copy(
                            isCompleted = true,
                            completedAt = rg.completedAt ?: System.currentTimeMillis()
                        )
                    )
                    itemsSynced++
                }
                if (rg.isCompleted) {
                    progressDao.insertXpTransaction(
                        XpTransactionEntity(
                            userId = userId,
                            actionKey = "DAILY_GOAL_${rg.dateString}_${rg.goalKey}",
                            reason = "Synced Daily Goal: ${rg.title}",
                            xpAmount = rg.xpReward
                        )
                    )
                }
            }

            // 5. PULL remote Mistakes -> Merge into Room
            val remoteMistakes = client.fetchMistakes(localUser.email, accessToken).getOrNull().orEmpty()
            val localMistakesMap = progressDao.getMistakes(userId).associateBy { it.questionId }
            for (rm in remoteMistakes) {
                if (rm.questionId.isBlank()) continue
                val existing = localMistakesMap[rm.questionId]
                if (existing == null) {
                    progressDao.insertMistake(
                        MistakeEntity(
                            userId = userId,
                            questionId = rm.questionId,
                            questionTitle = rm.questionTitle,
                            difficulty = rm.difficulty,
                            topic = rm.topic,
                            errorReason = rm.errorReason,
                            failedCodeSnippet = rm.failedCodeSnippet,
                            language = rm.language,
                            userNotes = rm.userNotes,
                            savedAt = rm.savedAt
                        )
                    )
                    itemsSynced++
                } else if (rm.savedAt > existing.savedAt) {
                    progressDao.updateMistake(
                        existing.copy(
                            errorReason = rm.errorReason,
                            failedCodeSnippet = rm.failedCodeSnippet,
                            language = rm.language,
                            userNotes = rm.userNotes,
                            savedAt = rm.savedAt
                        )
                    )
                    itemsSynced++
                }
            }

            // 6. PULL remote Bookmarks -> Merge into Room
            val remoteBookmarks = client.fetchBookmarks(localUser.email, accessToken).getOrNull().orEmpty()
            val localBookmarkIds = progressDao.getBookmarks(userId).map { it.questionId }.toSet()
            for (rb in remoteBookmarks) {
                if (rb.questionId.isBlank()) continue
                if (!localBookmarkIds.contains(rb.questionId)) {
                    progressDao.insertBookmark(
                        BookmarkEntity(
                            userId = userId,
                            questionId = rb.questionId,
                            questionTitle = rb.questionTitle,
                            difficulty = rb.difficulty,
                            topic = rb.topic,
                            savedAt = rb.savedAt
                        )
                    )
                    itemsSynced++
                }
            }

            // 7. RESTORE unlocked Achievements & Badges WITHOUT double-awarding XP
            restoreAchievementsAndBadgesWithoutXp(
                userId = userId,
                user = localUser,
                solvedList = allSolvedNow,
                topics = progressDao.getTopicProgressList(userId),
                completedGoalsCount = progressDao.getTotalCompletedDailyGoalsAllTime(userId)
            )

            // 8. PUSH merged state UP to Supabase PostgreSQL
            val latestUser = userDao.getUserById(userId) ?: localUser
            val profilePushRes = client.upsertProfile(
                SupabaseProfile(
                    id = remoteProfile?.id,
                    email = latestUser.email,
                    fullName = latestUser.fullName,
                    avatarKey = latestUser.avatarKey,
                    isCustomPhoto = latestUser.isCustomPhoto,
                    interests = latestUser.interests,
                    preferredLanguage = latestUser.preferredLanguage,
                    xp = latestUser.xp,
                    level = latestUser.level,
                    coins = latestUser.coins,
                    currentStreak = latestUser.currentStreak,
                    longestStreak = latestUser.longestStreak,
                    lastQualifyingDate = latestUser.lastQualifyingDate,
                    passwordHash = latestUser.passwordHash
                ),
                accessToken = accessToken
            )
            if (profilePushRes.isFailure) {
                return@withContext SupabaseSyncResult.Error(
                    profilePushRes.exceptionOrNull()?.localizedMessage ?: "Failed to push profile to Supabase."
                )
            }
            itemsSynced++

            if (allSolvedNow.isNotEmpty()) {
                val progressDtoList = allSolvedNow.map { solve ->
                    SupabaseProgress(
                        userEmail = latestUser.email,
                        questionId = solve.questionId,
                        topic = solve.topic,
                        difficulty = solve.difficulty,
                        solvedAt = solve.solvedAt
                    )
                }
                client.upsertUserProgress(progressDtoList, accessToken).onSuccess { itemsSynced += it }
            }

            val localGoalsToday = progressDao.getDailyGoals(userId, todayDate)
            if (localGoalsToday.isNotEmpty()) {
                val goalDtoList = localGoalsToday.map { goal ->
                    SupabaseDailyGoal(
                        userEmail = latestUser.email,
                        dateString = goal.dateString,
                        goalKey = goal.goalKey,
                        title = goal.title,
                        xpReward = goal.xpReward,
                        isCompleted = goal.isCompleted,
                        completedAt = goal.completedAt
                    )
                }
                client.upsertDailyGoals(goalDtoList, accessToken).onSuccess { itemsSynced += it }
            }

            val localMistakes = progressDao.getMistakes(userId)
            if (localMistakes.isNotEmpty()) {
                val mistakeDtoList = localMistakes.map { m ->
                    SupabaseMistake(
                        userEmail = latestUser.email,
                        questionId = m.questionId,
                        questionTitle = m.questionTitle,
                        difficulty = m.difficulty,
                        topic = m.topic,
                        errorReason = m.errorReason,
                        failedCodeSnippet = m.failedCodeSnippet,
                        language = m.language,
                        userNotes = m.userNotes,
                        savedAt = m.savedAt
                    )
                }
                client.upsertMistakes(mistakeDtoList, accessToken).onSuccess { itemsSynced += it }
            }

            val localBookmarks = progressDao.getBookmarks(userId)
            if (localBookmarks.isNotEmpty()) {
                val bookmarkDtoList = localBookmarks.map { b ->
                    SupabaseBookmark(
                        userEmail = latestUser.email,
                        questionId = b.questionId,
                        questionTitle = b.questionTitle,
                        difficulty = b.difficulty,
                        topic = b.topic,
                        savedAt = b.savedAt
                    )
                }
                client.upsertBookmarks(bookmarkDtoList, accessToken).onSuccess { itemsSynced += it }
            }

            SupabaseSyncResult.Success(
                message = "Supabase Cloud Database synchronized ($itemsSynced items synced).",
                syncedCount = itemsSynced
            )
        } catch (e: Exception) {
            SupabaseSyncResult.Error("Supabase sync failed: ${e.localizedMessage ?: "Network error"}")
        }
    }

    private suspend fun ensureTopicsAndTodayGoalsExist(userId: Long, currentDate: String) {
        if (progressDao.getTopicProgressList(userId).size < 8) {
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
        if (progressDao.getDailyGoals(userId, currentDate).size < 8) {
            val initialGoals = listOf(
                DailyGoalEntity(userId = userId, dateString = currentDate, goalKey = LearningRepository.GOAL_SOLVE_EASY, title = "Solve 1 Easy Problem", xpReward = 20),
                DailyGoalEntity(userId = userId, dateString = currentDate, goalKey = LearningRepository.GOAL_SOLVE_MEDIUM, title = "Solve 1 Medium Problem", xpReward = 30),
                DailyGoalEntity(userId = userId, dateString = currentDate, goalKey = LearningRepository.GOAL_SOLVE_HARD, title = "Solve 1 Hard Problem", xpReward = 45),
                DailyGoalEntity(userId = userId, dateString = currentDate, goalKey = LearningRepository.GOAL_DAILY_CHALLENGE, title = "Complete Daily Challenge", xpReward = 50),
                DailyGoalEntity(userId = userId, dateString = currentDate, goalKey = LearningRepository.GOAL_CONCEPT_QUIZ, title = "Complete Concept Quiz", xpReward = 10),
                DailyGoalEntity(userId = userId, dateString = currentDate, goalKey = LearningRepository.GOAL_LEARN_15_MIN, title = "Learn for 15 Minutes", xpReward = 15),
                DailyGoalEntity(userId = userId, dateString = currentDate, goalKey = LearningRepository.GOAL_REVIEW_MISTAKE, title = "Review One Mistake", xpReward = 15),
                DailyGoalEntity(userId = userId, dateString = currentDate, goalKey = LearningRepository.GOAL_ASK_AI_MENTOR, title = "Ask AI Mentor", xpReward = 10)
            )
            progressDao.insertDailyGoals(initialGoals)
        }
    }

    private suspend fun restoreAchievementsAndBadgesWithoutXp(
        userId: Long,
        user: UserEntity,
        solvedList: List<SolvedQuestionEntity>,
        topics: List<TopicProgressEntity>,
        completedGoalsCount: Int
    ) {
        val solvedCount = solvedList.size
        val maxStreak = max(user.currentStreak, user.longestStreak)
        val achKeys = mutableListOf<String>()
        if (solvedCount >= 1) achKeys.add(GamificationCatalog.ACH_FIRST_SOLVE)
        if (solvedCount >= 5) achKeys.add(GamificationCatalog.ACH_SOLVE_5)
        if (solvedCount >= 10) achKeys.add(GamificationCatalog.ACH_SOLVE_10)
        if (solvedCount >= 50) achKeys.add(GamificationCatalog.ACH_SOLVE_50)
        if (solvedCount >= 100) achKeys.add(GamificationCatalog.ACH_SOLVE_100)
        if (topics.any { it.solvedCount >= it.maxCount && it.maxCount > 0 }) {
            achKeys.add(GamificationCatalog.ACH_TOPIC_COMPLETION)
        }
        if (maxStreak >= 3) achKeys.add(GamificationCatalog.ACH_STREAK_3)
        if (maxStreak >= 7) achKeys.add(GamificationCatalog.ACH_STREAK_7)
        if (user.xp >= 100) achKeys.add(GamificationCatalog.ACH_XP_100)
        if (user.xp >= 500) achKeys.add(GamificationCatalog.ACH_XP_500)

        for (key in achKeys) {
            progressDao.insertUserAchievement(UserAchievementEntity(userId = userId, achievementKey = key))
            val reward = GamificationCatalog.ALL_ACHIEVEMENTS.firstOrNull { it.key == key }?.xpReward ?: 0
            if (reward > 0) {
                progressDao.insertXpTransaction(
                    XpTransactionEntity(
                        userId = userId,
                        actionKey = "ACHIEVEMENT_REWARD_$key",
                        reason = "Synced achievement $key",
                        xpAmount = reward
                    )
                )
            }
        }

        val badgeKeys = mutableListOf<String>()
        if (completedGoalsCount >= 1) badgeKeys.add(GamificationCatalog.BADGE_CODE_INITIATE)
        if (solvedList.count { it.difficulty.equals("Easy", ignoreCase = true) } >= 3) {
            badgeKeys.add(GamificationCatalog.BADGE_EASY_CONQUEROR)
        }
        if (solvedList.count { it.difficulty.equals("Medium", ignoreCase = true) } >= 3) {
            badgeKeys.add(GamificationCatalog.BADGE_MEDIUM_TACTICIAN)
        }
        if (solvedList.count { it.difficulty.equals("Hard", ignoreCase = true) } >= 1) {
            badgeKeys.add(GamificationCatalog.BADGE_HARD_LEGEND)
        }
        if (maxStreak >= 3) badgeKeys.add(GamificationCatalog.BADGE_STREAK_GUARDIAN)
        if (topics.any { it.solvedCount >= it.maxCount && it.maxCount > 0 }) {
            badgeKeys.add(GamificationCatalog.BADGE_TOPIC_CHAMPION)
        }
        for (bKey in badgeKeys) {
            progressDao.insertUserBadge(UserBadgeEntity(userId = userId, badgeKey = bKey))
        }
    }

    // ==========================================
    // GLOBAL LEADERBOARD FROM SUPABASE
    // ==========================================

    suspend fun getGlobalLeaderboard(limit: Int = 50): List<LeaderboardEntryDto> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext emptyList()
        val result = client.fetchLeaderboard(limit)
        val profiles = result.getOrNull().orEmpty()
        profiles.mapIndexed { index, profile ->
            LeaderboardEntryDto(
                rank = index + 1,
                userId = profile.email,
                displayName = profile.fullName.ifBlank { "Coder" },
                avatarKey = profile.avatarKey,
                level = profile.level,
                totalXp = profile.xp,
                streak = profile.currentStreak,
                solvedCount = 0
            )
        }
    }
}
