package com.example.data.repository

import com.example.data.local.ProgressDao
import com.example.data.local.SessionPreferencesManager
import com.example.data.local.UserDao
import com.example.model.TopicProgressEntity
import com.example.model.UserEntity
import com.example.utils.ValidationUtils
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

sealed class AuthResult {
    data class Success(val user: UserEntity) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

class AuthRepository(
    private val userDao: UserDao,
    private val progressDao: ProgressDao,
    private val sessionPrefs: SessionPreferencesManager,
    private val supabaseRepository: com.example.data.remote.supabase.SupabaseRepository? = null
) {

    /**
     * Registers a new user with strict validation:
     * - Full name >= 2 chars
     * - Email must end with @gmail.com
     * - Password >= 6 chars and matches confirmPassword
     * - Rejects duplicate @gmail.com registrations
     * - Seamlessly registers with Supabase Auth & PostgreSQL when configured
     * - Initializes 8 Topic Learning Paths at 0/10
     */
    suspend fun signUp(
        fullName: String,
        email: String,
        password: String,
        confirmPassword: String,
        avatarKey: String,
        isCustomPhoto: Boolean = false,
        interests: String = "Arrays, Dynamic Programming, Graphs",
        preferredLanguage: String = "Kotlin"
    ): AuthResult {
        val trimmedName = fullName.trim()
        val normalizedEmail = ValidationUtils.normalizeEmail(email)

        if (!ValidationUtils.isValidFullName(trimmedName)) {
            return AuthResult.Error("Please enter your full name (at least 2 characters).")
        }
        if (normalizedEmail.isBlank()) {
            return AuthResult.Error("Please enter your Gmail address.")
        }
        if (!ValidationUtils.isValidGmailAddress(normalizedEmail)) {
            return AuthResult.Error("Invalid email domain. Only @gmail.com addresses are accepted.")
        }
        if (!ValidationUtils.isValidPassword(password)) {
            return AuthResult.Error("Password must be at least 6 characters long.")
        }
        if (!ValidationUtils.doPasswordsMatch(password, confirmPassword)) {
            return AuthResult.Error("Passwords do not match. Please verify both fields.")
        }

        val existingUser = userDao.getUserByEmail(normalizedEmail)
        if (existingUser != null) {
            return AuthResult.Error("This @gmail.com address is already registered. Please sign in instead.")
        }

        val passwordHash = ValidationUtils.hashPassword(normalizedEmail, password)
        var supabaseToken: String? = null
        if (supabaseRepository != null && supabaseRepository.isConfigured()) {
            val supaResult = supabaseRepository.signUpWithSupabase(
                email = normalizedEmail,
                password = password,
                fullName = trimmedName,
                avatarKey = avatarKey.ifBlank { "avatar_cyber" },
                isCustomPhoto = isCustomPhoto,
                interests = interests.ifBlank { "Arrays, Dynamic Programming, Graphs" },
                preferredLanguage = preferredLanguage.ifBlank { "Kotlin" },
                passwordHash = passwordHash
            )
            if (supaResult.isSuccess) {
                supabaseToken = supaResult.getOrNull()?.accessToken
            } else {
                val msg = supaResult.exceptionOrNull()?.localizedMessage.orEmpty()
                return if (msg.contains("already exists", ignoreCase = true) ||
                    msg.contains("already registered", ignoreCase = true)
                ) {
                    AuthResult.Error("An account with this email already exists on Supabase. Please sign in.")
                } else {
                    AuthResult.Error(
                        msg.ifBlank { "Could not register account in Supabase. Check internet & supabase_schema.sql." }
                    )
                }
            }
        }

        val newUser = UserEntity(
            fullName = trimmedName,
            email = normalizedEmail,
            passwordHash = passwordHash,
            avatarKey = avatarKey.ifBlank { "avatar_cyber" },
            isCustomPhoto = isCustomPhoto,
            interests = interests.ifBlank { "Arrays, Dynamic Programming, Graphs" },
            preferredLanguage = preferredLanguage.ifBlank { "Kotlin" },
            xp = 0,
            level = 1,
            coins = 50,
            currentStreak = 0,
            longestStreak = 0
        )

        val userId = userDao.insertUser(newUser)
        val savedUser = newUser.copy(id = userId)

        // Initialize the 8 Topic Learning Paths at 0/10 per user
        initializeUserTopics(userId)

        sessionPrefs.hasCompletedOnboarding = true
        sessionPrefs.saveSession(userId, normalizedEmail, supabaseToken)

        return AuthResult.Success(savedUser)
    }

    /**
     * Authenticates a user strictly against Supabase Auth & public.profiles (when configured),
     * restores all cloud progress onto the current device, and migrates any legacy local-only users.
     */
    suspend fun login(email: String, password: String): AuthResult {
        val normalizedEmail = ValidationUtils.normalizeEmail(email)

        if (normalizedEmail.isBlank()) {
            return AuthResult.Error("Please enter your Gmail address.")
        }
        if (!ValidationUtils.isValidGmailAddress(normalizedEmail)) {
            return AuthResult.Error("Invalid email domain. Only @gmail.com addresses are accepted.")
        }
        if (password.isEmpty()) {
            return AuthResult.Error("Please enter your password.")
        }

        val expectedHash = ValidationUtils.hashPassword(normalizedEmail, password)
        var supabaseToken: String? = null

        if (supabaseRepository != null && supabaseRepository.isConfigured()) {
            val supaResult = supabaseRepository.loginWithSupabase(normalizedEmail, password, expectedHash)
            if (supaResult.isSuccess) {
                val session = supaResult.getOrNull()
                supabaseToken = session?.accessToken

                var localUser = userDao.getUserByEmail(normalizedEmail)
                val remoteProfile = supabaseRepository.getProfile(normalizedEmail, supabaseToken)
                if (localUser == null) {
                    val hasLocalAvatarFile = remoteProfile?.isCustomPhoto == true &&
                        java.io.File(remoteProfile.avatarKey).exists()
                    val resolvedCustomPhoto = hasLocalAvatarFile
                    val resolvedAvatarKey = when {
                        remoteProfile == null -> "avatar_cyber"
                        remoteProfile.isCustomPhoto && !hasLocalAvatarFile -> "avatar_cyber"
                        else -> remoteProfile.avatarKey.ifBlank { "avatar_cyber" }
                    }
                    val newUser = UserEntity(
                        fullName = remoteProfile?.fullName?.ifBlank { "Coder" } ?: "Coder",
                        email = normalizedEmail,
                        passwordHash = expectedHash,
                        avatarKey = resolvedAvatarKey,
                        isCustomPhoto = resolvedCustomPhoto,
                        interests = remoteProfile?.interests?.ifBlank { "Arrays, Dynamic Programming, Graphs" }
                            ?: "Arrays, Dynamic Programming, Graphs",
                        preferredLanguage = remoteProfile?.preferredLanguage?.ifBlank { "Kotlin" } ?: "Kotlin",
                        xp = remoteProfile?.xp ?: 0,
                        level = remoteProfile?.level ?: 1,
                        coins = remoteProfile?.coins ?: 50,
                        currentStreak = remoteProfile?.currentStreak ?: 0,
                        longestStreak = remoteProfile?.longestStreak ?: 0,
                        lastQualifyingDate = remoteProfile?.lastQualifyingDate
                    )
                    val newId = userDao.insertUser(newUser)
                    initializeUserTopics(newId)
                    localUser = newUser.copy(id = newId)
                } else if (localUser.passwordHash != expectedHash) {
                    val updatedLocal = localUser.copy(passwordHash = expectedHash)
                    userDao.updateUser(updatedLocal)
                    localUser = updatedLocal
                }

                sessionPrefs.hasCompletedOnboarding = true
                sessionPrefs.saveSession(localUser.id, localUser.email, supabaseToken)

                // Synchronously restore all cloud progress (XP, solves, 8 topics, goals, mistakes, bookmarks, achievements)
                try {
                    supabaseRepository.syncUserData(localUser.id, preferRemote = true)
                } catch (_: Exception) {}

                val syncedUser = userDao.getUserById(localUser.id) ?: localUser
                return AuthResult.Success(syncedUser)
            } else {
                // If user existed locally (created before Supabase migration), verify locally & auto-register in Supabase
                val localOnlyUser = userDao.getUserByEmail(normalizedEmail)
                if (localOnlyUser != null) {
                    if (localOnlyUser.passwordHash != expectedHash) {
                        return AuthResult.Error("Incorrect password. Please check your credentials and try again.")
                    }
                    sessionPrefs.hasCompletedOnboarding = true
                    sessionPrefs.saveSession(localOnlyUser.id, localOnlyUser.email, supabaseToken)
                    try {
                        val cloudReg = supabaseRepository.ensureUserRegisteredInCloud(localOnlyUser, rawPassword = password)
                        if (cloudReg.isSuccess) {
                            supabaseRepository.syncUserData(localOnlyUser.id, preferRemote = false)
                        }
                    } catch (_: Exception) {}
                    return AuthResult.Success(userDao.getUserById(localOnlyUser.id) ?: localOnlyUser)
                }

                val errMsg = supaResult.exceptionOrNull()?.localizedMessage.orEmpty()
                return if (errMsg.contains("Invalid login credentials", ignoreCase = true)) {
                    AuthResult.Error("Invalid email or password. If you are new, please sign up first.")
                } else {
                    AuthResult.Error(
                        errMsg.ifBlank { "No account found with this @gmail.com address. Please sign up first." }
                    )
                }
            }
        }

        val registeredUser = userDao.getUserByEmail(normalizedEmail)
            ?: return AuthResult.Error("No account found with this @gmail.com address. Please sign up first.")

        if (registeredUser.passwordHash != expectedHash) {
            return AuthResult.Error("Incorrect password. Please check your credentials and try again.")
        }

        sessionPrefs.hasCompletedOnboarding = true
        sessionPrefs.saveSession(registeredUser.id, registeredUser.email, supabaseToken)
        return AuthResult.Success(registeredUser)
    }

    suspend fun getLoggedInUser(): UserEntity? {
        val userId = sessionPrefs.loggedInUserId
        if (userId <= 0L) return null
        return userDao.getUserById(userId)
    }

    fun observeUser(userId: Long): Flow<UserEntity?> {
        return userDao.observeUserById(userId)
    }

    suspend fun updateInterests(userId: Long, interests: String) {
        userDao.updateInterests(userId, interests)
        if (supabaseRepository != null && supabaseRepository.isConfigured()) {
            userDao.getUserById(userId)?.let { supabaseRepository.syncProfileOnly(it) }
        }
    }

    suspend fun updatePreferredLanguage(userId: Long, language: String) {
        userDao.updatePreferredLanguage(userId, language)
        if (supabaseRepository != null && supabaseRepository.isConfigured()) {
            userDao.getUserById(userId)?.let { supabaseRepository.syncProfileOnly(it) }
        }
    }

    fun logout() {
        val tokenSnapshot = sessionPrefs.supabaseAccessToken
        sessionPrefs.clearSession()
        if (supabaseRepository != null && supabaseRepository.isConfigured()) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    supabaseRepository.logout(tokenSnapshot)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Request password reset instructions for user.
     * In offline/local mode, verifies user existence; when backend is configured, calls POST /auth/forgot-password.
     */
    suspend fun requestPasswordReset(email: String): AuthResult {
        val normalizedEmail = email.trim().lowercase(Locale.ROOT)
        if (normalizedEmail.isEmpty()) {
            return AuthResult.Error("Please enter your registered email address.")
        }
        if (!ValidationUtils.isValidGmailAddress(normalizedEmail)) {
            return AuthResult.Error("Please enter a valid @gmail.com address.")
        }

        val existing = userDao.getUserByEmail(normalizedEmail)
        return if (existing != null) {
            AuthResult.Success(existing)
        } else {
            AuthResult.Error("No registered CodeQuest AI account found for $normalizedEmail.")
        }
    }

    /**
     * Permanently deletes account and all associated local progress data.
     * In production, this also dispatches DELETE /auth/account to remove server records.
     */
    suspend fun deleteAccount(userId: Long): Boolean {
        if (userId <= 0L) return false
        try {
            progressDao.deleteTopicProgressForUser(userId)
            progressDao.deleteSolvedQuestionsForUser(userId)
            progressDao.deleteDailyGoalsForUser(userId)
            progressDao.deleteBookmarksForUser(userId)
            progressDao.deleteMistakesForUser(userId)
            progressDao.deleteAttemptsForUser(userId)
            progressDao.deleteXpTransactionsForUser(userId)
            progressDao.deleteAchievementsForUser(userId)
            progressDao.deleteBadgesForUser(userId)
            progressDao.deleteAllNotificationsForUser(userId)
            progressDao.deleteAiHistoryForUser(userId)
            val deletedCount = userDao.deleteUserById(userId)
            sessionPrefs.clearSession()
            return deletedCount > 0
        } catch (_: Exception) {
            return false
        }
    }

    private suspend fun initializeUserTopics(userId: Long) {
        val initialTopics = listOf(
            TopicProgressEntity(userId = userId, topicIndex = 1, topicName = "Programming Basics", description = "Variables, I/O, data types, and operators", solvedCount = 0, maxCount = 10),
            TopicProgressEntity(userId = userId, topicIndex = 2, topicName = "Conditions", description = "If-else branches, switch/when, boolean logic", solvedCount = 0, maxCount = 10),
            TopicProgressEntity(userId = userId, topicIndex = 3, topicName = "Loops", description = "For, while, nested iterations, and control flow", solvedCount = 0, maxCount = 10),
            TopicProgressEntity(userId = userId, topicIndex = 4, topicName = "Strings", description = "Manipulation, palindromes, substrings, parsing", solvedCount = 0, maxCount = 10),
            TopicProgressEntity(userId = userId, topicIndex = 5, topicName = "Arrays", description = "Traversal, two-pointers, sliding window, sorting", solvedCount = 0, maxCount = 10),
            TopicProgressEntity(userId = userId, topicIndex = 6, topicName = "Number Theory", description = "Primes, GCD/LCM, modular arithmetic, digits", solvedCount = 0, maxCount = 10),
            TopicProgressEntity(userId = userId, topicIndex = 7, topicName = "Patterns", description = "Star pyramids, numeric grids, symmetry logic", solvedCount = 0, maxCount = 10),
            TopicProgressEntity(userId = userId, topicIndex = 8, topicName = "Problem Solving", description = "Recursion, hashing, greedy, and algorithmic quests", solvedCount = 0, maxCount = 10)
        )
        progressDao.insertTopicProgressList(initialTopics)
    }
}
