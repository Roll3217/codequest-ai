package com.example.data.repository

import com.example.data.remote.AIMentorRequest
import com.example.data.remote.AIAnalyzeCodeRequest
import com.example.data.remote.CodeQuestProductionApiService
import com.example.data.remote.GeminiApiClient
import com.example.data.remote.LearnerProfileContext
import com.example.utils.AppConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Production AI Repository.
 * Architecture:
 * - Production: Android App -> CodeQuest Backend (POST /ai/mentor & POST /ai/analyze-code) -> Server-Side Gemini API
 *   (API keys are NEVER bundled inside the APK).
 * - Development fallback: When backend is not yet deployed, routes to development client if developer entered
 *   GEMINI_API_KEY into their local environment / Secrets panel.
 */
class AiRepository(
    private val sessionTokenProvider: () -> String? = { null }
) {

    private val productionApiService: CodeQuestProductionApiService? by lazy {
        val baseUrl = AppConfig.backendBaseUrl
        if (!AppConfig.isBackendConfigured()) null
        else {
            val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            Retrofit.Builder()
                .baseUrl(normalized)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create())
                .build()
                .create(CodeQuestProductionApiService::class.java)
        }
    }

    suspend fun askMentor(
        prompt: String,
        codeContext: String? = null,
        learner: LearnerProfileContext? = null,
        onStreamChunk: ((String) -> Unit)? = null
    ): Result<String> {
        // 1. If production backend is configured, use secure server-side endpoint
        if (AppConfig.isBackendConfigured() && productionApiService != null) {
            val token = sessionTokenProvider() ?: "Bearer dev_token"
            return try {
                val req = AIMentorRequest(
                    language = learner?.preferredLanguage ?: "Kotlin",
                    prompt = prompt,
                    codeContext = codeContext
                )
                val response = productionApiService!!.askAiMentor(token, req)
                Result.success(response.answerText)
            } catch (e: Exception) {
                Result.failure(Exception("Backend AI Mentor service error: ${e.localizedMessage ?: "Request failed"}"))
            }
        }

        // 2. Development fallback via isolated local environment key
        return if (onStreamChunk != null) {
            GeminiApiClient.askMentorStream(prompt, codeContext, learner) { chunk ->
                onStreamChunk(chunk)
            }
        } else {
            GeminiApiClient.askMentor(prompt, codeContext, learner)
        }
    }

    suspend fun analyzeCode(
        questionId: String,
        language: String,
        sourceCode: String,
        learner: LearnerProfileContext? = null
    ): Result<String> {
        // 1. Production backend endpoint
        if (AppConfig.isBackendConfigured() && productionApiService != null) {
            val token = sessionTokenProvider() ?: "Bearer dev_token"
            return try {
                val req = AIAnalyzeCodeRequest(
                    questionId = questionId,
                    language = language,
                    sourceCode = sourceCode
                )
                val response = productionApiService!!.askAiAnalyzeCode(token, req)
                val formatted = buildString {
                    append(response.analysisText)
                    append("\n\n• Time Complexity: ").append(response.timeComplexity)
                    append("\n• Space Complexity: ").append(response.spaceComplexity)
                    if (response.edgeCases.isNotEmpty()) {
                        append("\n\nEdge Cases:\n").append(response.edgeCases.joinToString("\n• ", prefix = "• "))
                    }
                    if (response.suggestions.isNotEmpty()) {
                        append("\n\nSuggestions:\n").append(response.suggestions.joinToString("\n• ", prefix = "• "))
                    }
                }
                Result.success(formatted)
            } catch (e: Exception) {
                Result.failure(Exception("Backend Code Analyzer service error: ${e.localizedMessage ?: "Request failed"}"))
            }
        }

        // 2. Development fallback
        val prompt = "Analyze this $language solution for problem '$questionId'. Provide time & space complexity, edge cases, and suggestions for improvement."
        return GeminiApiClient.askMentor(prompt, codeContext = sourceCode, learner = learner)
    }
}
