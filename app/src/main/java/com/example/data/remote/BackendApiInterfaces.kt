package com.example.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

// ==========================================
// 1. AUTHENTICATION CONTRACT MODELS
// ==========================================

data class SignUpRequest(
    val email: String,
    val passwordHash: String,
    val fullName: String,
    val avatarKey: String = "avatar_cyber",
    val interests: String = "Arrays, Dynamic Programming, Graphs",
    val preferredLanguage: String = "Kotlin"
)

data class LoginRequest(
    val email: String,
    val passwordHash: String
)

data class RefreshTokenRequest(
    val refreshToken: String
)

data class ForgotPasswordRequest(
    val email: String
)

data class ResetPasswordRequest(
    val email: String,
    val resetCode: String,
    val newPasswordHash: String
)

data class VerifyEmailRequest(
    val email: String,
    val verificationToken: String
)

data class AuthSessionResponse(
    val success: Boolean,
    val message: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Long = 3600L,
    val user: UserProfileDto? = null
)

data class GenericApiResponse(
    val success: Boolean,
    val message: String
)

// ==========================================
// 2. USER PROFILE CONTRACT MODELS
// ==========================================

data class UserProfileDto(
    val id: String,
    val email: String,
    val fullName: String,
    val avatarKey: String,
    val isCustomPhoto: Boolean = false,
    val interests: String = "Arrays, Dynamic Programming, Graphs",
    val preferredLanguage: String = "Kotlin",
    val xp: Int = 0,
    val level: Int = 1,
    val coins: Int = 50,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val emailVerified: Boolean = false
)

data class UpdateProfileRequest(
    val fullName: String? = null,
    val avatarKey: String? = null,
    val interests: String? = null,
    val preferredLanguage: String? = null
)

// ==========================================
// 3. PROGRESS & SOLVED QUESTIONS CONTRACT
// ==========================================

data class RemoteTopicProgressDto(
    val topicIndex: Int,
    val topicName: String,
    val solvedCount: Int,
    val maxCount: Int
)

data class UserProgressSummaryDto(
    val totalSolved: Int,
    val totalXp: Int,
    val level: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val topics: List<RemoteTopicProgressDto>,
    val solvedQuestionIds: List<String>
)

data class RecordSolveRequest(
    val questionId: String,
    val language: String,
    val submittedCode: String,
    val executionTimeMs: Long = 0L,
    val memoryKb: Long = 0L
)

data class RecordSolveResponse(
    val success: Boolean,
    val firstTimeSolved: Boolean,
    val xpAwarded: Int,
    val newTotalXp: Int,
    val newLevel: Int,
    val newStreak: Int,
    val message: String
)

// ==========================================
// 4. DAILY GOALS CONTRACT
// ==========================================

data class RemoteDailyGoalDto(
    val goalKey: String,
    val title: String,
    val xpReward: Int,
    val isCompleted: Boolean,
    val completedAt: Long?
)

data class DailyGoalsResponse(
    val dateString: String,
    val goals: List<RemoteDailyGoalDto>,
    val allCompletedBonusAwarded: Boolean
)

data class CompleteGoalRequest(
    val dateString: String,
    val goalKey: String
)

data class CompleteGoalResponse(
    val success: Boolean,
    val goalKey: String,
    val xpAwarded: Int,
    val newTotalXp: Int,
    val message: String
)

// ==========================================
// 5. ACHIEVEMENTS & GAMIFICATION
// ==========================================

data class RemoteAchievementDto(
    val achievementKey: String,
    val unlocked: Boolean,
    val unlockedAt: Long?
)

data class AchievementsListResponse(
    val achievements: List<RemoteAchievementDto>,
    val totalUnlocked: Int
)

// ==========================================
// 6. SERVER-AUTHORITATIVE LEADERBOARD
// ==========================================

data class LeaderboardEntryDto(
    val rank: Int,
    val userId: String,
    val displayName: String,
    val avatarKey: String,
    val level: Int,
    val totalXp: Int,
    val streak: Int,
    val solvedCount: Int
)

data class LeaderboardResponse(
    val period: String, // "weekly" | "all_time"
    val userRank: Int,
    val entries: List<LeaderboardEntryDto>
)

// ==========================================
// 7. BOOKMARKS & MISTAKES CONTRACT
// ==========================================

data class RemoteBookmarkDto(
    val questionId: String,
    val savedAt: Long
)

data class BookmarkActionRequest(
    val questionId: String
)

data class RemoteMistakeDto(
    val questionId: String,
    val questionTitle: String,
    val difficulty: String,
    val topic: String,
    val errorReason: String,
    val failedCodeSnippet: String,
    val language: String,
    val userNotes: String = "",
    val savedAt: Long
)

data class SaveMistakeRequest(
    val questionId: String,
    val errorReason: String,
    val failedCodeSnippet: String,
    val language: String,
    val userNotes: String = ""
)

// ==========================================
// 8. AI MENTOR & CODE ANALYSIS (SERVER-SIDE GEMINI)
// ==========================================

data class AIMentorRequest(
    val language: String,
    val prompt: String,
    val codeContext: String? = null
)

data class AIMentorResponse(
    val answerText: String,
    val intuition: String? = null,
    val codeSnippet: String? = null,
    val timeComplexity: String? = null,
    val spaceComplexity: String? = null,
    val commonPitfalls: String? = null
)

data class AIAnalyzeCodeRequest(
    val questionId: String,
    val language: String,
    val sourceCode: String
)

data class AIAnalyzeCodeResponse(
    val analysisText: String,
    val timeComplexity: String,
    val spaceComplexity: String,
    val edgeCases: List<String>,
    val suggestions: List<String>
)

// ==========================================
// 9. REMOTE CODE EXECUTION SANDBOX
// ==========================================

data class ExecuteTestCaseDto(
    val input: String,
    val expectedOutput: String
)

data class CodeExecutionRequest(
    val questionId: String,
    val language: String,
    val sourceCode: String,
    val stdin: String? = null,
    val testCases: List<ExecuteTestCaseDto> = emptyList(),
    val timeoutSeconds: Int = 10
)

data class TestCaseRunResultDto(
    val index: Int,
    val passed: Boolean,
    val input: String,
    val expectedOutput: String,
    val actualOutput: String,
    val error: String? = null
)

data class CodeExecutionResponse(
    val compileStatus: String, // "SUCCESS" | "FAILED" | "TIMEOUT"
    val executionStatus: String, // "ACCEPTED" | "WRONG_ANSWER" | "RUNTIME_ERROR" | "TIMEOUT"
    val passed: Boolean,
    val stdout: String,
    val stderr: String,
    val errorLine: Int? = null,
    val errorColumn: Int? = null,
    val testCasesPassed: Int,
    val testCasesTotal: Int,
    val testResults: List<TestCaseRunResultDto> = emptyList(),
    val executionTimeMs: Long,
    val memoryKb: Long,
    val inputGiven: String? = null,
    val expectedOutput: String? = null,
    val actualOutput: String? = null,
    val errorTitle: String? = null,
    val errorExplanation: String? = null,
    val fixSuggestion: String? = null
)

// ==========================================
// 10. RETROFIT REST API SERVICE CONTRACT
// ==========================================

interface CodeQuestProductionApiService {

    // --- Authentication ---
    @POST("auth/signup")
    suspend fun signUp(@Body request: SignUpRequest): AuthSessionResponse

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): AuthSessionResponse

    @POST("auth/logout")
    suspend fun logout(@Header("Authorization") bearerToken: String): GenericApiResponse

    @POST("auth/refresh")
    suspend fun refreshToken(@Body request: RefreshTokenRequest): AuthSessionResponse

    @POST("auth/forgot-password")
    suspend fun forgotPassword(@Body request: ForgotPasswordRequest): GenericApiResponse

    @POST("auth/reset-password")
    suspend fun resetPassword(@Body request: ResetPasswordRequest): GenericApiResponse

    @POST("auth/verify-email")
    suspend fun verifyEmail(@Body request: VerifyEmailRequest): GenericApiResponse

    @DELETE("auth/account")
    suspend fun deleteAccount(@Header("Authorization") bearerToken: String): GenericApiResponse

    // --- User Profile ---
    @GET("user/profile")
    suspend fun getProfile(@Header("Authorization") bearerToken: String): UserProfileDto

    @PUT("user/profile")
    suspend fun updateProfile(
        @Header("Authorization") bearerToken: String,
        @Body request: UpdateProfileRequest
    ): UserProfileDto

    // --- Progress & Solving ---
    @GET("progress")
    suspend fun getProgress(@Header("Authorization") bearerToken: String): UserProgressSummaryDto

    @POST("progress/solve")
    suspend fun recordSolve(
        @Header("Authorization") bearerToken: String,
        @Body request: RecordSolveRequest
    ): RecordSolveResponse

    // --- Daily Goals ---
    @GET("daily-goals")
    suspend fun getDailyGoals(
        @Header("Authorization") bearerToken: String,
        @Query("date") dateString: String
    ): DailyGoalsResponse

    @POST("daily-goals/complete")
    suspend fun completeDailyGoal(
        @Header("Authorization") bearerToken: String,
        @Body request: CompleteGoalRequest
    ): CompleteGoalResponse

    // --- Achievements ---
    @GET("achievements")
    suspend fun getAchievements(@Header("Authorization") bearerToken: String): AchievementsListResponse

    // --- Leaderboard ---
    @GET("leaderboard")
    suspend fun getLeaderboard(
        @Header("Authorization") bearerToken: String,
        @Query("period") period: String = "weekly"
    ): LeaderboardResponse

    // --- Bookmarks ---
    @GET("bookmarks")
    suspend fun getBookmarks(@Header("Authorization") bearerToken: String): List<RemoteBookmarkDto>

    @POST("bookmarks")
    suspend fun addBookmark(
        @Header("Authorization") bearerToken: String,
        @Body request: BookmarkActionRequest
    ): GenericApiResponse

    @DELETE("bookmarks/{id}")
    suspend fun removeBookmark(
        @Header("Authorization") bearerToken: String,
        @Path("id") questionId: String
    ): GenericApiResponse

    // --- Mistakes Notebook ---
    @GET("mistakes")
    suspend fun getMistakes(@Header("Authorization") bearerToken: String): List<RemoteMistakeDto>

    @POST("mistakes")
    suspend fun saveMistake(
        @Header("Authorization") bearerToken: String,
        @Body request: SaveMistakeRequest
    ): GenericApiResponse

    // --- AI Mentor & Code Analysis (Server-side Gemini) ---
    @POST("ai/mentor")
    suspend fun askAiMentor(
        @Header("Authorization") bearerToken: String,
        @Body request: AIMentorRequest
    ): AIMentorResponse

    @POST("ai/analyze-code")
    suspend fun askAiAnalyzeCode(
        @Header("Authorization") bearerToken: String,
        @Body request: AIAnalyzeCodeRequest
    ): AIAnalyzeCodeResponse

    // --- Isolated Code Execution Sandbox ---
    @POST("code/execute")
    suspend fun executeCode(
        @Header("Authorization") bearerToken: String,
        @Body request: CodeExecutionRequest
    ): CodeExecutionResponse
}
