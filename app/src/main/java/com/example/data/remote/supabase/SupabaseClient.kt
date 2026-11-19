package com.example.data.remote.supabase

import com.example.utils.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

class SupabaseClient(
    private val baseUrlOverride: String? = null,
    private val anonKeyOverride: String? = null
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun getBaseUrl(): String {
        val raw = (baseUrlOverride ?: AppConfig.supabaseUrl).trim()
        return if (raw.endsWith("/")) raw.dropLast(1) else raw
    }

    private fun getAnonKey(): String = (anonKeyOverride ?: AppConfig.supabaseAnonKey).trim()

    internal fun isClientConfigured(): Boolean {
        return getBaseUrl().startsWith("http") && getAnonKey().isNotBlank()
    }

    private fun normEmail(email: String): String = email.trim().lowercase(Locale.ROOT)

    private fun createBaseRequestBuilder(path: String, accessToken: String? = null): Request.Builder {
        val url = "${getBaseUrl()}/$path"
        val key = getAnonKey()
        val authHeader = if (!accessToken.isNullOrBlank()) "Bearer $accessToken" else "Bearer $key"
        return Request.Builder()
            .url(url)
            .addHeader("apikey", key)
            .addHeader("Authorization", authHeader)
            .addHeader("Content-Type", "application/json")
    }

    /**
     * Checks if Supabase is reachable and the anon key is accepted.
     */
    suspend fun ping(): Boolean = pingDiagnostic().reachable

    /**
     * Returns detailed connection + schema table diagnostics for Settings and debugging.
     */
    suspend fun pingDiagnostic(): SupabaseDiagnostic = withContext(Dispatchers.IO) {
        if (!isClientConfigured()) {
            return@withContext SupabaseDiagnostic(
                reachable = false,
                tablesReady = false,
                statusLabel = "○ LOCAL MODE",
                detailMessage = "Cloud sync is not configured in this build. Progress is safely stored on-device."
            )
        }
        try {
            val request = createBaseRequestBuilder("rest/v1/profiles?select=email&limit=1")
                .get()
                .build()
            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> SupabaseDiagnostic(
                        reachable = true,
                        tablesReady = true,
                        statusLabel = "● CONNECTED & READY",
                        detailMessage = "Connected to cloud sync (${AppConfig.supabaseHost}). All sync tables are active for multi-device login."
                    )
                    resp.code == 404 || body.contains("PGRST205", ignoreCase = true) ||
                        body.contains("does not exist", ignoreCase = true) -> SupabaseDiagnostic(
                        reachable = true,
                        tablesReady = false,
                        statusLabel = "⚠ SCHEMA MISSING",
                        detailMessage = "Reached ${AppConfig.supabaseHost}, but 'public.profiles' table was not found. Please initialize the database schema."
                    )
                    resp.code == 401 || resp.code == 403 -> SupabaseDiagnostic(
                        reachable = false,
                        tablesReady = false,
                        statusLabel = "⚠ AUTH KEY ERROR",
                        detailMessage = "Cloud service rejected the project API key (HTTP ${resp.code})."
                    )
                    else -> SupabaseDiagnostic(
                        reachable = false,
                        tablesReady = false,
                        statusLabel = "⚠ CONNECTION ERROR",
                        detailMessage = extractErrorMessage(body, "Supabase returned HTTP ${resp.code}.")
                    )
                }
            }
        } catch (e: Exception) {
            SupabaseDiagnostic(
                reachable = false,
                tablesReady = false,
                statusLabel = "○ OFFLINE / UNREACHABLE",
                detailMessage = "Could not reach ${AppConfig.supabaseHost}: ${e.localizedMessage ?: "Network error"}"
            )
        }
    }

    // ==========================================
    // AUTHENTICATION (GoTrue)
    // ==========================================

    suspend fun signUp(
        email: String,
        password: String,
        fullName: String,
        avatarKey: String = "avatar_cyber",
        interests: String = "Arrays, Dynamic Programming, Graphs",
        preferredLanguage: String = "Kotlin"
    ): Result<SupabaseAuthResponse> = withContext(Dispatchers.IO) {
        if (!isClientConfigured()) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured."))
        }

        val normalizedEmail = normEmail(email)
        try {
            val userMetadata = JSONObject().apply {
                put("full_name", fullName)
                put("avatar_key", avatarKey)
                put("interests", interests)
                put("preferred_language", preferredLanguage)
            }
            val payload = JSONObject().apply {
                put("email", normalizedEmail)
                put("password", password)
                put("data", userMetadata)
            }

            val request = createBaseRequestBuilder("auth/v1/signup")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                val bodyString = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val json = JSONObject(bodyString)
                    val accessToken = json.optString("access_token", "")
                    val refreshToken = json.optString("refresh_token", "")
                    val userObj = json.optJSONObject("user")
                    val userId = (userObj?.optString("id", "") ?: "")
                        .ifBlank { json.optString("id", "") }
                    val identities = json.optJSONArray("identities") ?: userObj?.optJSONArray("identities")

                    // When Confirm Email is ON in Supabase GoTrue and the email already exists in auth.users,
                    // GoTrue returns HTTP 200 with an empty identities array and no access_token.
                    if (accessToken.isBlank() && identities != null && identities.length() == 0) {
                        return@withContext Result.failure(Exception("User already registered"))
                    }

                    Result.success(
                        SupabaseAuthResponse(
                            accessToken = accessToken.ifBlank { null },
                            refreshToken = refreshToken.ifBlank { null },
                            user = SupabaseUser(id = userId, email = normalizedEmail)
                        )
                    )
                } else {
                    val errorMsg = extractErrorMessage(bodyString, "Supabase signup failed (${resp.code})")
                    Result.failure(Exception(errorMsg))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun login(email: String, password: String): Result<SupabaseAuthResponse> = withContext(Dispatchers.IO) {
        if (!isClientConfigured()) {
            return@withContext Result.failure(IllegalStateException("Supabase is not configured."))
        }

        val normalizedEmail = normEmail(email)
        try {
            val payload = JSONObject().apply {
                put("email", normalizedEmail)
                put("password", password)
            }

            val request = createBaseRequestBuilder("auth/v1/token?grant_type=password")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                val bodyString = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val json = JSONObject(bodyString)
                    val accessToken = json.optString("access_token", "")
                    val refreshToken = json.optString("refresh_token", "")
                    val userObj = json.optJSONObject("user")
                    val userId = (userObj?.optString("id", "") ?: "")
                        .ifBlank { json.optString("id", "") }

                    Result.success(
                        SupabaseAuthResponse(
                            accessToken = accessToken.ifBlank { null },
                            refreshToken = refreshToken.ifBlank { null },
                            user = SupabaseUser(id = userId, email = normalizedEmail)
                        )
                    )
                } else {
                    val errorMsg = extractErrorMessage(bodyString, "Invalid email or password.")
                    Result.failure(Exception(errorMsg))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout(accessToken: String): Boolean = withContext(Dispatchers.IO) {
        if (!isClientConfigured() || accessToken.isBlank()) return@withContext false
        try {
            val request = createBaseRequestBuilder("auth/v1/logout", accessToken)
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            val response = okHttpClient.newCall(request).execute()
            response.use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    // ==========================================
    // DATABASE / POSTGREST OPERATIONS
    // ==========================================

    suspend fun getProfile(email: String, accessToken: String? = null): Result<SupabaseProfile?> = withContext(Dispatchers.IO) {
        if (!isClientConfigured()) return@withContext Result.success(null)
        val normalizedEmail = normEmail(email)
        try {
            val encodedEmail = URLEncoder.encode(normalizedEmail, "UTF-8")
            val request = createBaseRequestBuilder("rest/v1/profiles?email=eq.$encodedEmail&select=*&limit=1", accessToken)
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val errorMsg = when {
                        resp.code == 404 || body.contains("PGRST205", ignoreCase = true) ||
                            body.contains("Could not find the table", ignoreCase = true) ||
                            body.contains("does not exist", ignoreCase = true) ->
                            "Supabase 'profiles' table not found. Open Supabase SQL Editor and run 'supabase_schema.sql'."
                        resp.code == 401 || resp.code == 403 || body.contains("42501", ignoreCase = true) ||
                            body.contains("row-level security", ignoreCase = true) ->
                            "Supabase rejected reading profiles (RLS policy or invalid anon key). Check SUPABASE_ANON_KEY and run 'supabase_schema.sql'."
                        else -> extractErrorMessage(body, "Failed to query profiles (${resp.code})")
                    }
                    return@withContext Result.failure(Exception(errorMsg))
                }
                val array = JSONArray(body)
                if (array.length() == 0) return@withContext Result.success(null)

                val item = array.getJSONObject(0)
                val profile = parseProfile(item, normalizedEmail)
                Result.success(profile)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun upsertProfile(profile: SupabaseProfile, accessToken: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isClientConfigured()) return@withContext Result.success(Unit)
        val normalizedEmail = normEmail(profile.email)

        fun buildProfileJson(includeExtendedColumns: Boolean): JSONObject = JSONObject().apply {
            if (!profile.id.isNullOrBlank()) {
                put("id", profile.id)
            }
            put("email", normalizedEmail)
            put("full_name", profile.fullName.ifBlank { "Coder" })
            put("avatar_key", profile.avatarKey.ifBlank { "avatar_cyber" })
            put("is_custom_photo", profile.isCustomPhoto)
            put("interests", profile.interests)
            put("preferred_language", profile.preferredLanguage.ifBlank { "Kotlin" })
            put("xp", profile.xp)
            put("level", profile.level)
            put("coins", profile.coins)
            put("current_streak", profile.currentStreak)
            put("longest_streak", profile.longestStreak)
            if (includeExtendedColumns) {
                if (!profile.lastQualifyingDate.isNullOrBlank()) {
                    put("last_qualifying_date", profile.lastQualifyingDate)
                }
                if (profile.passwordHash.isNotBlank()) {
                    put("password_hash", profile.passwordHash)
                }
            }
            put("updated_at", System.currentTimeMillis())
        }

        suspend fun executePost(path: String, payload: JSONObject, prefer: String? = null): Pair<Int, String> {
            val builder = createBaseRequestBuilder(path, accessToken)
            if (!prefer.isNullOrBlank()) {
                builder.addHeader("Prefer", prefer)
            }
            val request = builder
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()
            val response = okHttpClient.newCall(request).execute()
            return response.use { resp ->
                resp.code to resp.body?.string().orEmpty()
            }
        }

        suspend fun executePatchByEmail(payload: JSONObject): Pair<Int, String> {
            val encodedEmail = URLEncoder.encode(normalizedEmail, "UTF-8")
            val request = createBaseRequestBuilder("rest/v1/profiles?email=eq.$encodedEmail", accessToken)
                .addHeader("Prefer", "return=representation")
                .patch(payload.toString().toRequestBody(jsonMediaType))
                .build()
            val response = okHttpClient.newCall(request).execute()
            return response.use { resp ->
                resp.code to resp.body?.string().orEmpty()
            }
        }

        suspend fun upsertOrFallbackPayload(payload: JSONObject): Pair<Int, String> {
            val (code, body) = executePost(
                "rest/v1/profiles?on_conflict=email",
                payload,
                "resolution=merge-duplicates,return=representation"
            )
            if (code in 200..299) return code to body

            // If table uses `id` as PK without UNIQUE constraint on `email` (PostgreSQL 42P10)
            if (code == 400 && (
                    body.contains("42P10", ignoreCase = true) ||
                        body.contains("no unique or exclusion constraint", ignoreCase = true)
                    )
            ) {
                val existing = getProfile(normalizedEmail, accessToken).getOrNull()
                return if (existing != null) {
                    executePatchByEmail(payload)
                } else {
                    executePost("rest/v1/profiles", payload, "return=representation")
                }
            }
            return code to body
        }

        fun formatUpsertFailure(code: Int, body: String): Exception {
            val message = when {
                code == 404 || body.contains("PGRST205", ignoreCase = true) ||
                    body.contains("does not exist", ignoreCase = true) ->
                    "Supabase 'profiles' table not found. Open Supabase SQL Editor and run 'supabase_schema.sql'."
                code == 401 || code == 403 || body.contains("42501", ignoreCase = true) ||
                    body.contains("row-level security", ignoreCase = true) ->
                    "Supabase rejected saving profile (RLS policy or invalid anon key). Re-run 'supabase_schema.sql' in Supabase SQL Editor."
                else -> extractErrorMessage(body, "Failed to save Supabase profile ($code)")
            }
            return Exception(message)
        }

        try {
            val (code, body) = upsertOrFallbackPayload(buildProfileJson(includeExtendedColumns = true))
            if (code in 200..299) {
                return@withContext Result.success(Unit)
            }
            // Backward compatibility: if older schema lacks password_hash or last_qualifying_date, retry with base columns
            if (code == 400 && (
                    body.contains("PGRST204", ignoreCase = true) ||
                        body.contains("password_hash", ignoreCase = true) ||
                        body.contains("last_qualifying_date", ignoreCase = true) ||
                        body.contains("Could not find", ignoreCase = true)
                    )
            ) {
                val (retryCode, retryBody) = upsertOrFallbackPayload(buildProfileJson(includeExtendedColumns = false))
                if (retryCode in 200..299) {
                    return@withContext Result.success(Unit)
                }
                return@withContext Result.failure(formatUpsertFailure(retryCode, retryBody))
            }
            Result.failure(formatUpsertFailure(code, body))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun upsertUserProgress(progress: List<SupabaseProgress>, accessToken: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        if (!isClientConfigured() || progress.isEmpty()) return@withContext Result.success(0)
        try {
            val array = JSONArray()
            progress.forEach { item ->
                array.put(JSONObject().apply {
                    put("user_email", normEmail(item.userEmail))
                    put("question_id", item.questionId)
                    put("topic", item.topic)
                    put("difficulty", item.difficulty)
                    put("solved_at", item.solvedAt)
                })
            }

            val request = createBaseRequestBuilder("rest/v1/user_progress?on_conflict=user_email,question_id", accessToken)
                .addHeader("Prefer", "resolution=merge-duplicates")
                .post(array.toString().toRequestBody(jsonMediaType))
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(progress.size)
                else Result.failure(Exception(extractErrorMessage(body, "Failed to upsert progress: ${resp.code}")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchUserProgress(userEmail: String, accessToken: String? = null): Result<List<SupabaseProgress>> = withContext(Dispatchers.IO) {
        if (!isClientConfigured()) return@withContext Result.success(emptyList())
        val normalizedEmail = normEmail(userEmail)
        try {
            val encodedEmail = URLEncoder.encode(normalizedEmail, "UTF-8")
            val request = createBaseRequestBuilder("rest/v1/user_progress?user_email=eq.$encodedEmail&select=*&order=solved_at.desc", accessToken)
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception(extractErrorMessage(body, "Failed to fetch progress (${resp.code})")))
                }
                val array = JSONArray(body)
                val list = mutableListOf<SupabaseProgress>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        SupabaseProgress(
                            userEmail = obj.optString("user_email", normalizedEmail),
                            questionId = obj.optString("question_id", ""),
                            topic = obj.optString("topic", ""),
                            difficulty = obj.optString("difficulty", ""),
                            solvedAt = obj.optLong("solved_at", System.currentTimeMillis())
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun upsertDailyGoals(goals: List<SupabaseDailyGoal>, accessToken: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        if (!isClientConfigured() || goals.isEmpty()) return@withContext Result.success(0)
        try {
            val array = JSONArray()
            goals.forEach { item ->
                array.put(JSONObject().apply {
                    put("user_email", normEmail(item.userEmail))
                    put("date_string", item.dateString)
                    put("goal_key", item.goalKey)
                    put("title", item.title)
                    put("xp_reward", item.xpReward)
                    put("is_completed", item.isCompleted)
                    if (item.completedAt != null) put("completed_at", item.completedAt)
                })
            }

            val request = createBaseRequestBuilder("rest/v1/daily_goals?on_conflict=user_email,date_string,goal_key", accessToken)
                .addHeader("Prefer", "resolution=merge-duplicates")
                .post(array.toString().toRequestBody(jsonMediaType))
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(goals.size)
                else Result.failure(Exception(extractErrorMessage(body, "Failed to upsert daily goals: ${resp.code}")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchDailyGoals(
        userEmail: String,
        dateString: String? = null,
        accessToken: String? = null
    ): Result<List<SupabaseDailyGoal>> = withContext(Dispatchers.IO) {
        if (!isClientConfigured()) return@withContext Result.success(emptyList())
        val normalizedEmail = normEmail(userEmail)
        try {
            val encodedEmail = URLEncoder.encode(normalizedEmail, "UTF-8")
            val dateFilter = if (!dateString.isNullOrBlank()) {
                "&date_string=eq.${URLEncoder.encode(dateString, "UTF-8")}"
            } else ""
            val request = createBaseRequestBuilder(
                "rest/v1/daily_goals?user_email=eq.$encodedEmail$dateFilter&select=*",
                accessToken
            ).get().build()

            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception(extractErrorMessage(body, "Failed to fetch daily goals (${resp.code})")))
                }
                val array = JSONArray(body)
                val list = mutableListOf<SupabaseDailyGoal>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        SupabaseDailyGoal(
                            userEmail = obj.optString("user_email", normalizedEmail),
                            dateString = obj.optString("date_string", ""),
                            goalKey = obj.optString("goal_key", ""),
                            title = obj.optString("title", ""),
                            xpReward = obj.optInt("xp_reward", 20),
                            isCompleted = obj.optBoolean("is_completed", false),
                            completedAt = if (obj.isNull("completed_at")) null else obj.optLong("completed_at")
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun upsertMistakes(mistakes: List<SupabaseMistake>, accessToken: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        if (!isClientConfigured() || mistakes.isEmpty()) return@withContext Result.success(0)
        try {
            val array = JSONArray()
            mistakes.forEach { item ->
                array.put(JSONObject().apply {
                    put("user_email", normEmail(item.userEmail))
                    put("question_id", item.questionId)
                    put("question_title", item.questionTitle)
                    put("difficulty", item.difficulty)
                    put("topic", item.topic)
                    put("error_reason", item.errorReason)
                    put("failed_code_snippet", item.failedCodeSnippet)
                    put("language", item.language)
                    put("user_notes", item.userNotes)
                    put("saved_at", item.savedAt)
                })
            }

            val request = createBaseRequestBuilder("rest/v1/mistakes?on_conflict=user_email,question_id", accessToken)
                .addHeader("Prefer", "resolution=merge-duplicates")
                .post(array.toString().toRequestBody(jsonMediaType))
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(mistakes.size)
                else Result.failure(Exception(extractErrorMessage(body, "Failed to upsert mistakes: ${resp.code}")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchMistakes(userEmail: String, accessToken: String? = null): Result<List<SupabaseMistake>> = withContext(Dispatchers.IO) {
        if (!isClientConfigured()) return@withContext Result.success(emptyList())
        val normalizedEmail = normEmail(userEmail)
        try {
            val encodedEmail = URLEncoder.encode(normalizedEmail, "UTF-8")
            val request = createBaseRequestBuilder(
                "rest/v1/mistakes?user_email=eq.$encodedEmail&select=*&order=saved_at.desc",
                accessToken
            ).get().build()

            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception(extractErrorMessage(body, "Failed to fetch mistakes (${resp.code})")))
                }
                val array = JSONArray(body)
                val list = mutableListOf<SupabaseMistake>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        SupabaseMistake(
                            userEmail = obj.optString("user_email", normalizedEmail),
                            questionId = obj.optString("question_id", ""),
                            questionTitle = obj.optString("question_title", ""),
                            difficulty = obj.optString("difficulty", "Easy"),
                            topic = obj.optString("topic", "Basics"),
                            errorReason = obj.optString("error_reason", "Edge Case Review"),
                            failedCodeSnippet = obj.optString("failed_code_snippet", ""),
                            language = obj.optString("language", "Kotlin"),
                            userNotes = obj.optString("user_notes", ""),
                            savedAt = obj.optLong("saved_at", System.currentTimeMillis())
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteMistake(userEmail: String, questionId: String, accessToken: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isClientConfigured()) return@withContext Result.success(Unit)
        try {
            val encodedEmail = URLEncoder.encode(normEmail(userEmail), "UTF-8")
            val encodedQid = URLEncoder.encode(questionId, "UTF-8")
            val request = createBaseRequestBuilder(
                "rest/v1/mistakes?user_email=eq.$encodedEmail&question_id=eq.$encodedQid",
                accessToken
            ).delete().build()

            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) Result.success(Unit)
                else Result.failure(Exception("Failed to delete mistake (${resp.code})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun upsertBookmarks(bookmarks: List<SupabaseBookmark>, accessToken: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        if (!isClientConfigured() || bookmarks.isEmpty()) return@withContext Result.success(0)
        try {
            val array = JSONArray()
            bookmarks.forEach { item ->
                array.put(JSONObject().apply {
                    put("user_email", normEmail(item.userEmail))
                    put("question_id", item.questionId)
                    put("question_title", item.questionTitle)
                    put("difficulty", item.difficulty)
                    put("topic", item.topic)
                    put("saved_at", item.savedAt)
                })
            }

            val request = createBaseRequestBuilder("rest/v1/bookmarks?on_conflict=user_email,question_id", accessToken)
                .addHeader("Prefer", "resolution=merge-duplicates")
                .post(array.toString().toRequestBody(jsonMediaType))
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) Result.success(bookmarks.size)
                else Result.failure(Exception(extractErrorMessage(body, "Failed to upsert bookmarks: ${resp.code}")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchBookmarks(userEmail: String, accessToken: String? = null): Result<List<SupabaseBookmark>> = withContext(Dispatchers.IO) {
        if (!isClientConfigured()) return@withContext Result.success(emptyList())
        val normalizedEmail = normEmail(userEmail)
        try {
            val encodedEmail = URLEncoder.encode(normalizedEmail, "UTF-8")
            val request = createBaseRequestBuilder(
                "rest/v1/bookmarks?user_email=eq.$encodedEmail&select=*&order=saved_at.desc",
                accessToken
            ).get().build()

            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception(extractErrorMessage(body, "Failed to fetch bookmarks (${resp.code})")))
                }
                val array = JSONArray(body)
                val list = mutableListOf<SupabaseBookmark>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        SupabaseBookmark(
                            userEmail = obj.optString("user_email", normalizedEmail),
                            questionId = obj.optString("question_id", ""),
                            questionTitle = obj.optString("question_title", ""),
                            difficulty = obj.optString("difficulty", "Easy"),
                            topic = obj.optString("topic", "Basics"),
                            savedAt = obj.optLong("saved_at", System.currentTimeMillis())
                        )
                    )
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteBookmark(userEmail: String, questionId: String, accessToken: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isClientConfigured()) return@withContext Result.success(Unit)
        try {
            val encodedEmail = URLEncoder.encode(normEmail(userEmail), "UTF-8")
            val encodedQid = URLEncoder.encode(questionId, "UTF-8")
            val request = createBaseRequestBuilder(
                "rest/v1/bookmarks?user_email=eq.$encodedEmail&question_id=eq.$encodedQid",
                accessToken
            ).delete().build()

            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                if (resp.isSuccessful) Result.success(Unit)
                else Result.failure(Exception("Failed to delete bookmark (${resp.code})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchLeaderboard(limit: Int = 50): Result<List<SupabaseProfile>> = withContext(Dispatchers.IO) {
        if (!isClientConfigured()) return@withContext Result.success(emptyList())
        try {
            val request = createBaseRequestBuilder("rest/v1/profiles?order=xp.desc&limit=$limit&select=*")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) return@withContext Result.success(emptyList())
                val body = resp.body?.string().orEmpty()
                val array = JSONArray(body)
                val list = mutableListOf<SupabaseProfile>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    list.add(parseProfile(item, item.optString("email", "")))
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseProfile(item: JSONObject, fallbackEmail: String): SupabaseProfile {
        val lastDate = if (item.isNull("last_qualifying_date")) null
        else item.optString("last_qualifying_date", "").ifBlank { null }
        val passHash = if (item.isNull("password_hash")) ""
        else item.optString("password_hash", "")
        return SupabaseProfile(
            id = if (item.isNull("id")) null else item.optString("id"),
            email = item.optString("email", fallbackEmail),
            fullName = item.optString("full_name", "Coder"),
            avatarKey = item.optString("avatar_key", "avatar_cyber"),
            isCustomPhoto = item.optBoolean("is_custom_photo", false),
            interests = item.optString("interests", "Arrays, Dynamic Programming, Graphs"),
            preferredLanguage = item.optString("preferred_language", "Kotlin"),
            xp = item.optInt("xp", 0),
            level = item.optInt("level", 1),
            coins = item.optInt("coins", 50),
            currentStreak = item.optInt("current_streak", 0),
            longestStreak = item.optInt("longest_streak", 0),
            lastQualifyingDate = lastDate,
            passwordHash = passHash,
            updatedAt = item.optLong("updated_at", System.currentTimeMillis())
        )
    }

    private fun extractErrorMessage(bodyString: String, fallback: String): String {
        return try {
            val json = JSONObject(bodyString)
            json.optString("error_description", "")
                .ifBlank { json.optString("message", "") }
                .ifBlank { json.optString("msg", "") }
                .ifBlank { json.optString("hint", "") }
                .ifBlank { fallback }
        } catch (_: Exception) {
            if (bodyString.isNotBlank() && bodyString.length < 220) bodyString else fallback
        }
    }
}
