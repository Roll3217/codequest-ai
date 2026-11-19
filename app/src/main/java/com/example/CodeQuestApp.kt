package com.example

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.example.data.local.CodeQuestDatabase
import com.example.data.local.SessionPreferencesManager
import com.example.data.repository.AuthRepository
import com.example.data.repository.LearningRepository
import com.example.service.CodeExecutionService
import com.example.service.NotificationService
import com.example.service.RemoteCodeExecutionService
import com.example.service.RoomNotificationService
import com.example.utils.WindowInsetsHelper

class CodeQuestApp : Application() {

    val database: CodeQuestDatabase by lazy {
        CodeQuestDatabase.getInstance(this)
    }

    val sessionPreferences: SessionPreferencesManager by lazy {
        SessionPreferencesManager(this)
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(
            userDao = database.userDao(),
            progressDao = database.progressDao(),
            sessionPrefs = sessionPreferences,
            supabaseRepository = supabaseRepository
        )
    }

    val learningRepository: LearningRepository by lazy {
        LearningRepository(
            userDao = database.userDao(),
            questionDao = database.questionDao(),
            progressDao = database.progressDao(),
            sessionPreferences = sessionPreferences,
            supabaseRepository = supabaseRepository
        )
    }

    val codeExecutionService: CodeExecutionService by lazy {
        RemoteCodeExecutionService()
    }

    val notificationService: NotificationService by lazy {
        RoomNotificationService(learningRepository, sessionPreferences)
    }

    val aiRepository: com.example.data.repository.AiRepository by lazy {
        com.example.data.repository.AiRepository {
            sessionPreferences.loggedInUserEmail?.let { "Bearer session_token_$it" }
        }
    }

    val cloudSyncRepository: com.example.data.repository.CloudSyncRepository by lazy {
        com.example.data.repository.CloudSyncRepository(
            userDao = database.userDao(),
            questionDao = database.questionDao(),
            progressDao = database.progressDao(),
            tokenProvider = { sessionPreferences.loggedInUserEmail?.let { "Bearer session_token_$it" } },
            supabaseRepository = supabaseRepository
        )
    }

    val leaderboardRepository: com.example.data.repository.LeaderboardRepository by lazy {
        com.example.data.repository.LeaderboardRepository(
            userDao = database.userDao(),
            tokenProvider = { sessionPreferences.loggedInUserEmail?.let { "Bearer session_token_$it" } },
            supabaseRepository = supabaseRepository
        )
    }

    val supabaseRepository: com.example.data.remote.supabase.SupabaseRepository by lazy {
        com.example.data.remote.supabase.SupabaseRepository(
            userDao = database.userDao(),
            questionDao = database.questionDao(),
            progressDao = database.progressDao(),
            sessionPrefs = sessionPreferences
        )
    }

    override fun onCreate() {
        super.onCreate()
        sessionPreferences.applySavedTheme()
        learningRepository.onNotificationCreated = { notif ->
            notificationService.postNotificationEntity(this@CodeQuestApp, notif)
        }
        registerCentralWindowInsetsCallbacks()
    }

    private fun registerCentralWindowInsetsCallbacks() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is MainActivity) {
                    activity.window?.decorView?.post {
                        WindowInsetsHelper.applyStandardActivityInsets(activity)
                    }
                }
            }

            override fun onActivityStarted(activity: Activity) {
                if (activity !is MainActivity) {
                    WindowInsetsHelper.applyStandardActivityInsets(activity)
                }
            }

            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
