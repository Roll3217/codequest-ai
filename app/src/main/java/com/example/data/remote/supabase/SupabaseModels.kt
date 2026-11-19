package com.example.data.remote.supabase

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Supabase GoTrue & PostgREST data transfer models.
 */

@JsonClass(generateAdapter = true)
data class SupabaseAuthRequest(
    val email: String,
    val password: String,
    val data: Map<String, String>? = null
)

@JsonClass(generateAdapter = true)
data class SupabaseUser(
    val id: String,
    val email: String? = null,
    @property:Json(name = "user_metadata") val userMetadata: Map<String, Any?>? = null
)

@JsonClass(generateAdapter = true)
data class SupabaseAuthResponse(
    @property:Json(name = "access_token") val accessToken: String? = null,
    @property:Json(name = "token_type") val tokenType: String? = "bearer",
    @property:Json(name = "expires_in") val expiresIn: Long? = 3600L,
    @property:Json(name = "refresh_token") val refreshToken: String? = null,
    val user: SupabaseUser? = null,
    val error: String? = null,
    @property:Json(name = "error_description") val errorDescription: String? = null,
    val msg: String? = null
)

@JsonClass(generateAdapter = true)
data class SupabaseProfile(
    val id: String? = null,
    val email: String,
    @property:Json(name = "full_name") val fullName: String,
    @property:Json(name = "avatar_key") val avatarKey: String = "avatar_cyber",
    @property:Json(name = "is_custom_photo") val isCustomPhoto: Boolean = false,
    val interests: String = "Arrays, Dynamic Programming, Graphs",
    @property:Json(name = "preferred_language") val preferredLanguage: String = "Kotlin",
    val xp: Int = 0,
    val level: Int = 1,
    val coins: Int = 50,
    @property:Json(name = "current_streak") val currentStreak: Int = 0,
    @property:Json(name = "longest_streak") val longestStreak: Int = 0,
    @property:Json(name = "last_qualifying_date") val lastQualifyingDate: String? = null,
    @property:Json(name = "password_hash") val passwordHash: String = "",
    @property:Json(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

data class SupabaseDiagnostic(
    val reachable: Boolean,
    val tablesReady: Boolean,
    val statusLabel: String,
    val detailMessage: String
)

@JsonClass(generateAdapter = true)
data class SupabaseProgress(
    @property:Json(name = "user_email") val userEmail: String,
    @property:Json(name = "question_id") val questionId: String,
    val topic: String = "",
    val difficulty: String = "",
    @property:Json(name = "solved_at") val solvedAt: Long = System.currentTimeMillis()
)

@JsonClass(generateAdapter = true)
data class SupabaseDailyGoal(
    @property:Json(name = "user_email") val userEmail: String,
    @property:Json(name = "date_string") val dateString: String,
    @property:Json(name = "goal_key") val goalKey: String,
    val title: String,
    @property:Json(name = "xp_reward") val xpReward: Int,
    @property:Json(name = "is_completed") val isCompleted: Boolean,
    @property:Json(name = "completed_at") val completedAt: Long? = null
)

@JsonClass(generateAdapter = true)
data class SupabaseMistake(
    @property:Json(name = "user_email") val userEmail: String,
    @property:Json(name = "question_id") val questionId: String,
    @property:Json(name = "question_title") val questionTitle: String,
    val difficulty: String,
    val topic: String,
    @property:Json(name = "error_reason") val errorReason: String = "Edge Case Review",
    @property:Json(name = "failed_code_snippet") val failedCodeSnippet: String = "",
    val language: String = "Kotlin",
    @property:Json(name = "user_notes") val userNotes: String = "",
    @property:Json(name = "saved_at") val savedAt: Long = System.currentTimeMillis()
)

@JsonClass(generateAdapter = true)
data class SupabaseBookmark(
    @property:Json(name = "user_email") val userEmail: String,
    @property:Json(name = "question_id") val questionId: String,
    @property:Json(name = "question_title") val questionTitle: String,
    val difficulty: String,
    val topic: String,
    @property:Json(name = "saved_at") val savedAt: Long = System.currentTimeMillis()
)
