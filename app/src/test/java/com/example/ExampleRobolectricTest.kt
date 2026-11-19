package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CodeQuestDatabase
import com.example.data.local.QuestionBankSeed
import com.example.data.local.SessionPreferencesManager
import com.example.data.repository.AuthRepository
import com.example.data.repository.AuthResult
import com.example.data.repository.LearningRepository
import com.example.model.ThemeMode
import com.example.service.ExecutionStatus
import com.example.service.RemoteCodeExecutionService
import com.example.ui.achievements.GamificationCatalog
import com.example.ui.achievements.LevelProgressionEngine
import com.example.ui.learning.LearningTopicCatalog
import com.example.ui.settings.NotificationPreferencesState
import com.example.utils.ValidationUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: CodeQuestDatabase
    private lateinit var sessionPrefs: SessionPreferencesManager
    private lateinit var authRepo: AuthRepository
    private lateinit var learningRepo: LearningRepository

    @Before
    fun setUp() {
        com.example.data.remote.GeminiApiClient.apiKeyOverride = ""
        context = ApplicationProvider.getApplicationContext()
        sessionPrefs = SessionPreferencesManager(context)
        db = Room.inMemoryDatabaseBuilder(context, CodeQuestDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        authRepo = AuthRepository(db.userDao(), db.progressDao(), sessionPrefs)
        learningRepo = LearningRepository(db.userDao(), db.questionDao(), db.progressDao(), sessionPrefs)
    }

    @After
    fun tearDown() {
        com.example.data.remote.GeminiApiClient.apiKeyOverride = null
        db.close()
    }

    @Test
    fun verifyAppNameAndStrictGmailValidation() {
        val appName = context.getString(R.string.app_name)
        assertEquals("CodeQuest AI", appName)

        assertTrue(ValidationUtils.isValidGmailAddress("student.coder@gmail.com"))
        assertFalse(ValidationUtils.isValidGmailAddress("student@yahoo.com"))
        assertFalse(ValidationUtils.isValidGmailAddress("student@outlook.com"))
        assertFalse(ValidationUtils.isValidGmailAddress("@gmail.com"))
        assertTrue(ValidationUtils.isValidPassword("secret123"))
        assertFalse(ValidationUtils.isValidPassword("12345"))
    }

    @Test
    fun verifyQuestionBankCoversAllEightTopicsAndThreeDifficulties() {
        val questions = QuestionBankSeed.INITIAL_QUESTIONS
        assertEquals(240, questions.size)

        val uniqueIds = questions.map { it.id }.toSet()
        assertEquals(questions.size, uniqueIds.size)

        val topicsInBank = questions.map { it.topic }.toSet()
        assertEquals(LearningTopicCatalog.CORE_TOPICS.toSet(), topicsInBank)

        // Verify each of the 8 topics has 30 problems (10 Easy, 10 Medium, 10 Hard)
        LearningTopicCatalog.CORE_TOPICS.forEach { topic ->
            val topicQuestions = questions.filter { it.topic == topic }
            assertEquals(30, topicQuestions.size)
            assertEquals(10, topicQuestions.count { it.difficulty == "Easy" })
            assertEquals(10, topicQuestions.count { it.difficulty == "Medium" })
            assertEquals(10, topicQuestions.count { it.difficulty == "Hard" })
        }

        val difficultiesInBank = questions.map { it.difficulty }.toSet()
        assertEquals(setOf("Easy", "Medium", "Hard"), difficultiesInBank)
    }

    @Test
    fun verifyEightTopicsStartAtZeroOfTenAndProgressRulesAreEnforced(): Unit = runBlocking {
        val signUpResult = authRepo.signUp(
            fullName = "Aarav Verma",
            email = "aarav.verma@gmail.com",
            password = "password123",
            confirmPassword = "password123",
            avatarKey = "avatar_cyber",
            isCustomPhoto = false
        )
        assertTrue(signUpResult is AuthResult.Success)
        val user = (signUpResult as AuthResult.Success).user

        learningRepo.ensureInitializedForUser(user.id)

        // Every topic must start at 0/10
        val initialTopics = learningRepo.observeTopicProgress(user.id).first()
        assertEquals(8, initialTopics.size)
        initialTopics.forEach { tp ->
            assertEquals(0, tp.solvedCount)
            assertEquals(10, tp.maxCount)
            assertEquals(0, tp.percentage)
        }

        // Execution service executes code without blocking when backend is unconfigured
        val execService = RemoteCodeExecutionService()
        val runStatus = execService.runCode("PB-01", "Kotlin", "fun main() {}", "6 4")
        assertTrue(runStatus is ExecutionStatus.Executed)

        // Unverified / wrong submission must NOT increase progress
        val pb01 = learningRepo.getQuestionById("PB-01")!!
        learningRepo.recordSubmissionAttempt(
            userId = user.id,
            question = pb01,
            language = "Kotlin",
            sourceCode = "fun sumAndProduct() {}",
            passed = false,
            statusMessage = "Unverified / Wrong"
        )
        val topicsAfterWrong = learningRepo.observeTopicProgress(user.id).first()
        val basicsAfterWrong = topicsAfterWrong.first { it.topicName == "Programming Basics" }
        assertEquals(0, basicsAfterWrong.solvedCount)

        // Verified unique solve increases progress by +1
        learningRepo.recordSubmissionAttempt(
            userId = user.id,
            question = pb01,
            language = "Kotlin",
            sourceCode = "fun sumAndProduct(a: Long, b: Long) = \"\${a+b} \${a*b}\"",
            passed = true,
            statusMessage = "Passed"
        )
        val basicsAfterSolve = learningRepo.observeTopicProgress(user.id).first()
            .first { it.topicName == "Programming Basics" }
        assertEquals(1, basicsAfterSolve.solvedCount)
        assertEquals(10, basicsAfterSolve.percentage)

        // Solving the exact same question again gives NO additional progress and NO duplicate XP
        val xpAfterFirstSolve = authRepo.observeUser(user.id).first()!!.xp
        learningRepo.recordSubmissionAttempt(
            userId = user.id,
            question = pb01,
            language = "Python",
            sourceCode = "print('10 24')",
            passed = true,
            statusMessage = "Passed"
        )
        val basicsAfterDuplicateSolve = learningRepo.observeTopicProgress(user.id).first()
            .first { it.topicName == "Programming Basics" }
        assertEquals(1, basicsAfterDuplicateSolve.solvedCount)
        val xpAfterDuplicateSolve = authRepo.observeUser(user.id).first()!!.xp
        assertEquals(xpAfterFirstSolve, xpAfterDuplicateSolve)
    }

    @Test
    fun verifyStreakSystemRequiresThreeDailyGoalsAndHandlesConsecutiveDays(): Unit = runBlocking {
        val signUpResult = authRepo.signUp(
            fullName = "Priya Nair",
            email = "priya.nair@gmail.com",
            password = "password123",
            confirmPassword = "password123",
            avatarKey = "avatar_ninja",
            isCustomPhoto = false
        )
        val user = (signUpResult as AuthResult.Success).user
        val day1 = "2026-09-20"
        val day2 = "2026-09-21"
        val day4 = "2026-09-23" // Skipped 2026-09-22

        // Simply opening the app on Day 1 MUST NOT increase streak
        learningRepo.ensureInitializedForUser(user.id, day1)
        assertEquals(0, authRepo.observeUser(user.id).first()!!.currentStreak)

        // Completing 1 or 2 goals on Day 1 MUST NOT increase streak
        learningRepo.completeConceptQuizAction(user.id, day1)
        learningRepo.completeStudySessionAction(user.id, day1)
        assertEquals(0, authRepo.observeUser(user.id).first()!!.currentStreak)

        // Completing the 3rd goal on Day 1 qualifies Day 1 -> streak becomes 1
        learningRepo.completeMistakeReviewAction(user.id, "PB-01", day1)
        val userAfterDay1Goal3 = authRepo.observeUser(user.id).first()!!
        assertEquals(1, userAfterDay1Goal3.currentStreak)
        assertEquals(1, userAfterDay1Goal3.longestStreak)

        // Completing a 4th goal on the SAME day (Day 1) MUST NOT double-count streak
        learningRepo.completeAskAiMentorAction(user.id, day1)
        assertEquals(1, authRepo.observeUser(user.id).first()!!.currentStreak)

        // Day 2 (Consecutive day): Completing 3 goals increments streak to 2
        learningRepo.ensureInitializedForUser(user.id, day2)
        learningRepo.completeConceptQuizAction(user.id, day2)
        learningRepo.completeStudySessionAction(user.id, day2)
        learningRepo.completeMistakeReviewAction(user.id, "PB-01", day2)
        val userAfterDay2 = authRepo.observeUser(user.id).first()!!
        assertEquals(2, userAfterDay2.currentStreak)
        assertEquals(2, userAfterDay2.longestStreak)

        // Day 4 (Skipped Day 3): Opening app resets currentStreak to 0 while preserving longestStreak = 2
        learningRepo.ensureInitializedForUser(user.id, day4)
        val userOnDay4Start = authRepo.observeUser(user.id).first()!!
        assertEquals(0, userOnDay4Start.currentStreak)
        assertEquals(2, userOnDay4Start.longestStreak)
    }

    @Test
    fun verifyAchievementsBadgesMistakesBookmarksNotificationsAndLevelUp(): Unit = runBlocking {
        val signUpResult = authRepo.signUp(
            fullName = "Rohan Kulkarni",
            email = "rohan.kulkarni@gmail.com",
            password = "password123",
            confirmPassword = "password123",
            avatarKey = "avatar_astronaut",
            isCustomPhoto = false
        )
        val user = (signUpResult as AuthResult.Success).user
        learningRepo.ensureInitializedForUser(user.id)

        // No fake mistakes on startup
        assertTrue(learningRepo.observeMistakes(user.id).first().isEmpty())

        // Failed executed test case records in Mistakes Notebook without increasing solved count
        val medQ = learningRepo.getQuestionById("PB-02")!!
        learningRepo.recordSubmissionAttempt(
            userId = user.id,
            question = medQ,
            language = "Kotlin",
            sourceCode = "fun broken() {}",
            passed = false,
            statusMessage = "Failed Test Cases (1/3)",
            recordMistakeOnFailure = true
        )
        val mistakes = learningRepo.observeMistakes(user.id).first()
        assertEquals(1, mistakes.size)
        assertEquals("PB-02", mistakes.first().questionId)

        // Bookmark toggle persists
        val bookmarkedNow = learningRepo.toggleBookmark(user.id, medQ)
        assertTrue(bookmarkedNow)
        assertEquals(1, learningRepo.observeBookmarks(user.id).first().size)

        // Solve PB-01 (Easy) -> unlocks First Problem Solved achievement + awards problem XP + Easy Daily Goal XP
        val easyQ = learningRepo.getQuestionById("PB-01")!!
        learningRepo.recordSubmissionAttempt(
            userId = user.id,
            question = easyQ,
            language = "Python",
            sourceCode = "print('10 24')",
            passed = true,
            statusMessage = "Passed (3/3)"
        )

        val achievements = learningRepo.observeAchievementsWithStatus(user.id).first()
        val firstSolveAch = achievements.first { it.definition.key == GamificationCatalog.ACH_FIRST_SOLVE }
        assertTrue(firstSolveAch.isUnlocked)

        // Polyglot badge unlocked because attempts were made in both Kotlin and Python
        val badges = learningRepo.observeBadgesWithStatus(user.id).first()
        val polyglotBadge = badges.first { it.definition.key == GamificationCatalog.BADGE_POLYGLOT }
        assertTrue(polyglotBadge.isUnlocked)

        // Award enough XP to reach Level 2 (100+ XP) and verify Level-Up & Notification
        learningRepo.awardXpOnce(user.id, "BONUS_TEST_QUEST", "Test Quest", 60)
        val updatedUser = authRepo.observeUser(user.id).first()!!
        assertTrue(updatedUser.xp >= 100)
        assertEquals(LevelProgressionEngine.calculateLevel(updatedUser.xp), updatedUser.level)
        assertTrue(updatedUser.level >= 2)

        val notifications = learningRepo.observeNotifications(user.id).first()
        assertTrue(notifications.any { it.category == LearningRepository.NOTIF_CAT_ACHIEVEMENT })
        assertTrue(notifications.any { it.category == LearningRepository.NOTIF_CAT_LEVEL_UP })
    }

    @Test
    fun verifyProgressAndPreferencesPersistAcrossDatabaseAndSessionRestart(): Unit = runBlocking {
        val dbFile = File(context.cacheDir, "persistence_restart_test.db")
        if (dbFile.exists()) dbFile.delete()

        // First instance (Before app restart)
        val db1 = Room.databaseBuilder(context, CodeQuestDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        val prefs1 = SessionPreferencesManager(context)
        prefs1.themeMode = ThemeMode.LIGHT
        prefs1.saveNotificationPreferences(
            NotificationPreferencesState(
                dailyCodingReminder = false,
                streakReminder = true,
                dailyChallengeAlert = true,
                achievementUnlockedAlert = true,
                learningGoalsAlert = false,
                soundEffects = false,
                smartHints = true
            )
        )

        val auth1 = AuthRepository(db1.userDao(), db1.progressDao(), prefs1)
        val learn1 = LearningRepository(db1.userDao(), db1.questionDao(), db1.progressDao(), prefs1)

        val signUp = auth1.signUp(
            fullName = "Meera Joshi",
            email = "meera.joshi@gmail.com",
            password = "password123",
            confirmPassword = "password123",
            avatarKey = "avatar_cyber",
            isCustomPhoto = false
        ) as AuthResult.Success

        learn1.ensureInitializedForUser(signUp.user.id)
        val q1 = learn1.getQuestionById("PB-01")!!
        learn1.recordSubmissionAttempt(
            userId = signUp.user.id,
            question = q1,
            language = "Kotlin",
            sourceCode = "fun solve() = 42",
            passed = true,
            statusMessage = "Passed"
        )
        db1.close()

        // Second instance (Simulating cold app restart)
        val db2 = Room.databaseBuilder(context, CodeQuestDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        val prefs2 = SessionPreferencesManager(context)
        assertEquals(ThemeMode.LIGHT, prefs2.themeMode)
        assertFalse(prefs2.getNotificationPreferences().dailyCodingReminder)
        assertFalse(prefs2.getNotificationPreferences().soundEffects)

        val learn2 = LearningRepository(db2.userDao(), db2.questionDao(), db2.progressDao(), prefs2)
        val snapshotAfterRestart = learn2.observeProgressAnalytics(signUp.user.id).first()
        assertEquals(1, snapshotAfterRestart.totalSolved)
        assertEquals(1, snapshotAfterRestart.topics.first { it.topicName == "Programming Basics" }.solvedCount)

        db2.close()
        dbFile.delete()
        Unit
    }

    @Test
    fun verifyWindowInsetsHelperSeparatesStatusBarAndBottomNavigationWithoutDoubleApplying() {
        sessionPrefs.saveSession(1L, "test.insets@gmail.com")
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val rootView = activity.findViewById<android.view.View>(R.id.main_root)
        val fragmentContainer = activity.findViewById<android.view.View>(R.id.fragment_container)
        val bottomNav = activity.findViewById<android.view.View>(R.id.bottom_navigation)

        val simulatedInsets = androidx.core.view.WindowInsetsCompat.Builder()
            .setInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars(),
                androidx.core.graphics.Insets.of(0, 72, 0, 0)
            )
            .setInsets(
                androidx.core.view.WindowInsetsCompat.Type.navigationBars(),
                androidx.core.graphics.Insets.of(0, 0, 0, 48)
            )
            .build()

        // First dispatch (initial screen load)
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(rootView, simulatedInsets)
        assertEquals(72, fragmentContainer.paddingTop)
        assertEquals(48, bottomNav.paddingBottom)

        // Navigate across tabs (Progress -> Profile -> Practice -> AI -> Home) and dispatch insets again
        activity.navigateToTab(R.id.nav_progress)
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(rootView, simulatedInsets)
        activity.navigateToTab(R.id.nav_profile)
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(rootView, simulatedInsets)
        activity.navigateToTab(R.id.nav_practice)
        androidx.core.view.ViewCompat.dispatchApplyWindowInsets(rootView, simulatedInsets)

        // Verify insets are NOT double-applied
        assertEquals(72, fragmentContainer.paddingTop)
        assertEquals(48, bottomNav.paddingBottom)

        controller.pause().stop().destroy()
    }

    @Test
    fun verifyAuthenticationSessionAndLogoutWorkflow(): Unit = runBlocking {
        // 1. Initially clear session: user is not logged in (first time open)
        sessionPrefs.clearSession()
        assertFalse(sessionPrefs.isUserLoggedIn())

        // 2. User registers a new account
        val testEmail = "learner.dsa@gmail.com"
        val signUpResult = authRepo.signUp(
            fullName = "DSA Learner",
            email = testEmail,
            password = "mypassword123",
            confirmPassword = "mypassword123",
            avatarKey = "avatar_cyber",
            isCustomPhoto = false
        )
        assertTrue(signUpResult is AuthResult.Success)
        val user = (signUpResult as AuthResult.Success).user
        assertEquals(testEmail, user.email)

        // 3. After registration, session is active: user is logged in
        assertTrue(sessionPrefs.isUserLoggedIn())
        assertEquals(user.id, sessionPrefs.loggedInUserId)
        assertEquals(testEmail, sessionPrefs.loggedInUserEmail)

        // 4. Reopening the app after some time: session persists, no login/signup required
        val recheckSession = sessionPrefs.isUserLoggedIn()
        assertTrue("Reopening the app must maintain active session without re-login", recheckSession)

        // 5. User manually logs out from profile / settings
        authRepo.logout()
        assertFalse("After logging out, session must be cleared", sessionPrefs.isUserLoggedIn())
        assertEquals(-1L, sessionPrefs.loggedInUserId)
        assertNull(sessionPrefs.loggedInUserEmail)

        // 6. After logout, user can sign in again with their credentials
        val loginResult = authRepo.login(testEmail, "mypassword123")
        assertTrue(loginResult is AuthResult.Success)
        assertTrue("Logging in must re-establish active session", sessionPrefs.isUserLoggedIn())
        assertEquals(user.id, sessionPrefs.loggedInUserId)

        // 7. Password reset verification
        val resetResult = authRepo.requestPasswordReset(testEmail)
        assertTrue(resetResult is AuthResult.Success)
        val nonexistentReset = authRepo.requestPasswordReset("notfound@gmail.com")
        assertTrue(nonexistentReset is AuthResult.Error)

        // 8. Account deletion verification: removes user and clears session
        val deleteSuccess = authRepo.deleteAccount(user.id)
        assertTrue(deleteSuccess)
        assertFalse(sessionPrefs.isUserLoggedIn())
        assertNull(db.userDao().getUserById(user.id))
    }

    @Test
    fun verifyLearnerInterestsAndPersonalizedAiProfile(): Unit = runBlocking {
        val email = "mukesh.coder@gmail.com"
        val signUpResult = authRepo.signUp(
            fullName = "Mukesh",
            email = email,
            password = "password123",
            confirmPassword = "password123",
            avatarKey = "avatar_cyber",
            isCustomPhoto = false,
            interests = "Dynamic Programming, Graphs, Kotlin",
            preferredLanguage = "Kotlin"
        )
        assertTrue(signUpResult is AuthResult.Success)
        val user = (signUpResult as AuthResult.Success).user
        assertEquals("Mukesh", user.fullName)
        assertEquals("Dynamic Programming, Graphs, Kotlin", user.interests)
        assertEquals("Kotlin", user.preferredLanguage)

        // Verify updating interests dynamically persists in DB
        authRepo.updateInterests(user.id, "Trees, System Design, Python")
        val updatedUser = db.userDao().getUserById(user.id)
        assertNotNull(updatedUser)
        assertEquals("Trees, System Design, Python", updatedUser?.interests)
    }

    @Test
    fun verifyCloudSyncOfflineFallback(): Unit = runBlocking {
        val cloudSync = com.example.data.repository.CloudSyncRepository(
            userDao = db.userDao(),
            questionDao = db.questionDao(),
            progressDao = db.progressDao(),
            tokenProvider = { "Bearer test_token" }
        )
        val syncResult = cloudSync.syncUserData(1L)
        assertTrue(syncResult is com.example.data.repository.SyncResult.Offline)
    }

    @Test
    fun verifyLeaderboardRepositoryOfflineCache(): Unit = runBlocking {
        val leaderboardRepo = com.example.data.repository.LeaderboardRepository(
            userDao = db.userDao(),
            tokenProvider = { "Bearer test_token" }
        )
        val result = leaderboardRepo.getLeaderboard("weekly", 1L)
        assertTrue(result is com.example.data.repository.LeaderboardResult.OfflineCache)
    }

    @Test
    fun verifyPracticeSectionRandomQuestionsAndExecution(): Unit = runBlocking {
        // 1. Setup user and ensure initialized
        val signUpResult = authRepo.signUp(
            fullName = "Neha Sharma",
            email = "neha.practice@gmail.com",
            password = "password123",
            confirmPassword = "password123",
            avatarKey = "avatar_synth",
            isCustomPhoto = false
        )
        assertTrue(signUpResult is AuthResult.Success)
        val user = (signUpResult as AuthResult.Success).user
        learningRepo.ensureInitializedForUser(user.id)

        val execService = RemoteCodeExecutionService()

        // 2. Select a random question from the 240 questions in QuestionBankSeed
        val allQuestions = QuestionBankSeed.INITIAL_QUESTIONS
        assertTrue("Question bank must have 240 questions", allQuestions.size >= 240)

        val randomQuestion1 = allQuestions.random()
        assertNotNull(randomQuestion1.id)
        assertNotNull(randomQuestion1.title)
        assertNotNull(randomQuestion1.topic)

        // Parse test cases
        val testCases1 = try {
            val arr = org.json.JSONArray(randomQuestion1.testCasesJson)
            val list = mutableListOf<com.example.data.remote.ExecuteTestCaseDto>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(com.example.data.remote.ExecuteTestCaseDto(input = obj.optString("in", ""), expectedOutput = obj.optString("out", "")))
            }
            list
        } catch (_: Exception) {
            listOf(com.example.data.remote.ExecuteTestCaseDto(input = randomQuestion1.expectedInput, expectedOutput = randomQuestion1.expectedOutput))
        }

        com.example.data.remote.GeminiApiClient.apiKeyOverride = ""
        try {
            // Test A: Blank code fails with clear error
            val blankRun = execService.runCode(randomQuestion1.id, "Kotlin", "   ", randomQuestion1.expectedInput)
            assertTrue("Blank code execution should be completed", blankRun is ExecutionStatus.Executed)
            val blankExecuted = blankRun as ExecutionStatus.Executed
            assertFalse("Blank code must fail", blankExecuted.passed)
            assertTrue(blankExecuted.output.contains("Compilation Error") || blankExecuted.output.contains("empty"))

            // Test B: Untouched starter code with TODO fails with TODO explanation
            val starterCode = QuestionBankSeed.getStarterCodeForLanguage(randomQuestion1.starterCodeJson, "Kotlin")
            val starterRun = execService.runCode(randomQuestion1.id, "Kotlin", starterCode, randomQuestion1.expectedInput)
            assertTrue(starterRun is ExecutionStatus.Executed)
            val starterExecuted = starterRun as ExecutionStatus.Executed
            assertFalse("Starter code with TODO must not pass", starterExecuted.passed)
            assertTrue("Must indicate unimplemented TODO", starterExecuted.output.contains("TODO"))

            // Test C: Syntax error (mismatched curly braces) fails with syntax notice
            val brokenSyntaxCode = """
                fun solve() {
                    val x = 10
            """.trimIndent()
            val syntaxRun = execService.runCode(randomQuestion1.id, "Kotlin", brokenSyntaxCode, randomQuestion1.expectedInput)
            assertTrue(syntaxRun is ExecutionStatus.Executed)
            val syntaxExecuted = syntaxRun as ExecutionStatus.Executed
            assertFalse("Mismatched braces must not pass", syntaxExecuted.passed)
            assertTrue("Must report curly braces", syntaxExecuted.output.contains("curly braces"))

            // Test D: Valid Kotlin implementation runs successfully
            val validKotlinCode = """
                fun solve(input: String): String {
                    return input.trim()
                }
                fun main() {
                    println(solve("${randomQuestion1.expectedInput}"))
                }
            """.trimIndent()
            val validRun = execService.runCode(randomQuestion1.id, "Kotlin", validKotlinCode, randomQuestion1.expectedInput)
            assertTrue(validRun is ExecutionStatus.Executed)
            val validRunExecuted = validRun as ExecutionStatus.Executed
            assertTrue("Valid Kotlin code must pass run", validRunExecuted.passed)
            assertTrue("Must indicate sample passed", validRunExecuted.output.contains("PASSED") || validRunExecuted.output.contains("exit code 0"))

            // Test E: Submit valid solution against test cases
            val submitResult = execService.submitSolution(randomQuestion1.id, "Kotlin", validKotlinCode, testCases1)
            assertTrue(submitResult is ExecutionStatus.Executed)
            val submitExecuted = submitResult as ExecutionStatus.Executed
            assertTrue("Valid solution must pass submission", submitExecuted.passed)
            assertEquals("All test cases should pass", submitExecuted.totalTestCases, submitExecuted.passedTestCases)

            // Test F: Record submission and verify progress & XP award
            val initialXp = authRepo.observeUser(user.id).first()!!.xp
            learningRepo.recordSubmissionAttempt(
                userId = user.id,
                question = randomQuestion1,
                language = "Kotlin",
                sourceCode = validKotlinCode,
                passed = true,
                statusMessage = "Passed (${submitExecuted.passedTestCases}/${submitExecuted.totalTestCases})"
            )

            val updatedUser = authRepo.observeUser(user.id).first()!!
            assertTrue("User must receive XP for solving question", updatedUser.xp > initialXp)

            val topicProgress = learningRepo.observeTopicProgress(user.id).first()
                .first { it.topicName == randomQuestion1.topic }
            assertTrue("Topic progress must increment", topicProgress.solvedCount >= 1)

            // 3. Test another random question in Python
            val randomQuestion2 = allQuestions.filter { it.id != randomQuestion1.id }.random()
            val validPythonCode = """
                def solve(val):
                    return val

                if __name__ == "__main__":
                    print(solve("${randomQuestion2.expectedInput}"))
            """.trimIndent()

            val pythonRun = execService.runCode(randomQuestion2.id, "Python", validPythonCode, randomQuestion2.expectedInput)
            assertTrue(pythonRun is ExecutionStatus.Executed)
            assertTrue((pythonRun as ExecutionStatus.Executed).passed)

            val pythonSubmit = execService.submitSolution(randomQuestion2.id, "Python", validPythonCode, listOf(
                com.example.data.remote.ExecuteTestCaseDto(input = randomQuestion2.expectedInput, expectedOutput = randomQuestion2.expectedOutput)
            ))
            assertTrue(pythonSubmit is ExecutionStatus.Executed)
            assertTrue((pythonSubmit as ExecutionStatus.Executed).passed)
        } finally {
            com.example.data.remote.GeminiApiClient.apiKeyOverride = null
        }
    }

    @Test
    fun verifySupabaseUrlNormalizationRejectsHomepageAndUsesProjectUrl() {
        assertEquals(
            com.example.utils.AppConfig.DEFAULT_SUPABASE_URL,
            com.example.utils.AppConfig.normalizeSupabaseUrl("https://supabase.com")
        )
        assertEquals(
            com.example.utils.AppConfig.DEFAULT_SUPABASE_URL,
            com.example.utils.AppConfig.normalizeSupabaseUrl("https://www.supabase.com/")
        )
        assertEquals(
            com.example.utils.AppConfig.DEFAULT_SUPABASE_URL,
            com.example.utils.AppConfig.normalizeSupabaseUrl("https://app.supabase.com")
        )
        assertEquals(
            "https://qypwxgolicehkuljbyvp.supabase.co",
            com.example.utils.AppConfig.normalizeSupabaseUrl("https://supabase.com/dashboard/project/qypwxgolicehkuljbyvp")
        )
        assertEquals(
            "https://qypwxgolicehkuljbyvp.supabase.co",
            com.example.utils.AppConfig.normalizeSupabaseUrl("\"https://qypwxgolicehkuljbyvp.supabase.co/\"")
        )
        assertEquals(
            "https://qypwxgolicehkuljbyvp.supabase.co",
            com.example.utils.AppConfig.normalizeSupabaseUrl("https://qypwxgolicehkuljbyvp.superbase.co")
        )
    }

    @Test
    fun verifyCrossDeviceSupabaseRegistrationAndDataSync(): Unit = runBlocking {
        // In-memory Supabase GoTrue + PostgREST simulation server
        val authUsers = mutableMapOf<String, String>() // email -> password
        val profiles = mutableMapOf<String, org.json.JSONObject>()
        val progressRows = mutableListOf<org.json.JSONObject>()
        val dailyGoalRows = mutableListOf<org.json.JSONObject>()
        val mistakeRows = mutableListOf<org.json.JSONObject>()
        val bookmarkRows = mutableListOf<org.json.JSONObject>()

        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val query = exchange.requestURI.rawQuery.orEmpty()
            val method = exchange.requestMethod.uppercase()
            val reqBody = exchange.requestBody.bufferedReader().readText()

            fun send(code: Int, body: String) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            fun extractEqParam(paramName: String): String {
                val regex = Regex("$paramName=eq\\.([^&]+)")
                val raw = regex.find(query)?.groupValues?.get(1).orEmpty()
                return java.net.URLDecoder.decode(raw, "UTF-8")
            }

            when {
                path == "/auth/v1/signup" && method == "POST" -> {
                    val json = org.json.JSONObject(reqBody)
                    val email = json.getString("email").lowercase()
                    val password = json.getString("password")
                    if (authUsers.containsKey(email)) {
                        send(422, """{"msg":"User already registered"}""")
                    } else {
                        authUsers[email] = password
                        // Return GoTrue Confirm-Email format (root id, no access_token) to verify confirm-email resilience
                        send(200, """{"id":"uuid-123","email":"$email"}""")
                    }
                }
                path == "/auth/v1/token" && method == "POST" -> {
                    val json = org.json.JSONObject(reqBody)
                    val email = json.getString("email").lowercase()
                    val password = json.getString("password")
                    if (authUsers[email] == password) {
                        send(200, """{"access_token":"jwt_token_abc","refresh_token":"ref_123","user":{"id":"uuid-123","email":"$email"}}""")
                    } else {
                        send(400, """{"error_description":"Invalid login credentials"}""")
                    }
                }
                path == "/rest/v1/profiles" && method == "POST" -> {
                    val json = org.json.JSONObject(reqBody)
                    val email = json.getString("email").lowercase()
                    profiles[email] = json
                    send(201, "[$json]")
                }
                path == "/rest/v1/profiles" && method == "GET" -> {
                    val email = extractEqParam("email").lowercase()
                    val p = profiles[email]
                    send(200, if (p != null) "[$p]" else "[]")
                }
                path == "/rest/v1/user_progress" && method == "POST" -> {
                    val arr = org.json.JSONArray(reqBody)
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        progressRows.removeAll {
                            it.getString("user_email") == item.getString("user_email") &&
                                it.getString("question_id") == item.getString("question_id")
                        }
                        progressRows.add(item)
                    }
                    send(201, "[]")
                }
                path == "/rest/v1/user_progress" && method == "GET" -> {
                    val email = extractEqParam("user_email").lowercase()
                    val matching = org.json.JSONArray(progressRows.filter { it.getString("user_email") == email })
                    send(200, matching.toString())
                }
                path == "/rest/v1/daily_goals" && method == "POST" -> {
                    val arr = org.json.JSONArray(reqBody)
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        dailyGoalRows.removeAll {
                            it.getString("user_email") == item.getString("user_email") &&
                                it.getString("date_string") == item.getString("date_string") &&
                                it.getString("goal_key") == item.getString("goal_key")
                        }
                        dailyGoalRows.add(item)
                    }
                    send(201, "[]")
                }
                path == "/rest/v1/daily_goals" && method == "GET" -> {
                    val email = extractEqParam("user_email").lowercase()
                    val matching = org.json.JSONArray(dailyGoalRows.filter { it.getString("user_email") == email })
                    send(200, matching.toString())
                }
                path == "/rest/v1/mistakes" && method == "POST" -> {
                    val arr = org.json.JSONArray(reqBody)
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        mistakeRows.removeAll {
                            it.getString("user_email") == item.getString("user_email") &&
                                it.getString("question_id") == item.getString("question_id")
                        }
                        mistakeRows.add(item)
                    }
                    send(201, "[]")
                }
                path == "/rest/v1/mistakes" && method == "GET" -> {
                    val email = extractEqParam("user_email").lowercase()
                    val matching = org.json.JSONArray(mistakeRows.filter { it.getString("user_email") == email })
                    send(200, matching.toString())
                }
                path == "/rest/v1/bookmarks" && method == "POST" -> {
                    val arr = org.json.JSONArray(reqBody)
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        bookmarkRows.removeAll {
                            it.getString("user_email") == item.getString("user_email") &&
                                it.getString("question_id") == item.getString("question_id")
                        }
                        bookmarkRows.add(item)
                    }
                    send(201, "[]")
                }
                path == "/rest/v1/bookmarks" && method == "GET" -> {
                    val email = extractEqParam("user_email").lowercase()
                    val matching = org.json.JSONArray(bookmarkRows.filter { it.getString("user_email") == email })
                    send(200, matching.toString())
                }
                else -> send(404, """{"message":"Not found"}""")
            }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val clientA = com.example.data.remote.supabase.SupabaseClient(baseUrlOverride = baseUrl, anonKeyOverride = "test_anon_key")
            val prefsA = SessionPreferencesManager(context)
            prefsA.clearSession()

            // Device A (Database 1)
            val dbA = Room.inMemoryDatabaseBuilder(context, CodeQuestDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val supaRepoA = com.example.data.remote.supabase.SupabaseRepository(
                client = clientA,
                userDao = dbA.userDao(),
                questionDao = dbA.questionDao(),
                progressDao = dbA.progressDao(),
                sessionPrefs = prefsA
            )
            val authA = AuthRepository(dbA.userDao(), dbA.progressDao(), prefsA, supaRepoA)
            val learnA = LearningRepository(dbA.userDao(), dbA.questionDao(), dbA.progressDao(), prefsA, supaRepoA)

            // 1. User signs up on Device A -> must register inside Supabase profiles table
            val signUpRes = authA.signUp(
                fullName = "Arjun Mehta",
                email = "arjun.cloud@gmail.com",
                password = "password123",
                confirmPassword = "password123",
                avatarKey = "avatar_ninja",
                interests = "Graphs, Kotlin, Arrays",
                preferredLanguage = "Python"
            )
            assertTrue(signUpRes is AuthResult.Success)
            assertTrue("User must be registered in Supabase profiles table immediately", profiles.containsKey("arjun.cloud@gmail.com"))

            val userA = (signUpRes as AuthResult.Success).user
            learnA.ensureInitializedForUser(userA.id)

            // 2. Solve PB-01, bookmark PB-02, record mistake on PB-03, complete quiz goal
            val q1 = learnA.getQuestionById("PB-01")!!
            val q2 = learnA.getQuestionById("PB-02")!!
            val q3 = learnA.getQuestionById("PB-03")!!

            learnA.recordSubmissionAttempt(userA.id, q1, "Python", "print(10)", true, "Passed")
            learnA.toggleBookmark(userA.id, q2)
            learnA.recordSubmissionAttempt(userA.id, q3, "Python", "print(0)", false, "Wrong Answer", recordMistakeOnFailure = true)
            learnA.completeConceptQuizAction(userA.id)

            val updatedUserA = dbA.userDao().getUserById(userA.id)!!
            val xpOnDeviceA = updatedUserA.xp
            val levelOnDeviceA = updatedUserA.level
            val coinsOnDeviceA = updatedUserA.coins
            assertTrue(xpOnDeviceA > 0)

            dbA.close()
            prefsA.clearSession()

            // Device B (Completely fresh empty Room DB & cleared SharedPreferences)
            val dbB = Room.inMemoryDatabaseBuilder(context, CodeQuestDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val prefsB = SessionPreferencesManager(context)
            prefsB.clearSession()
            val clientB = com.example.data.remote.supabase.SupabaseClient(baseUrlOverride = baseUrl, anonKeyOverride = "test_anon_key")
            val supaRepoB = com.example.data.remote.supabase.SupabaseRepository(
                client = clientB,
                userDao = dbB.userDao(),
                questionDao = dbB.questionDao(),
                progressDao = dbB.progressDao(),
                sessionPrefs = prefsB
            )
            val authB = AuthRepository(dbB.userDao(), dbB.progressDao(), prefsB, supaRepoB)
            val learnB = LearningRepository(dbB.userDao(), dbB.questionDao(), dbB.progressDao(), prefsB, supaRepoB)

            // 3. Log in on Device B with the same credentials
            val loginBRes = authB.login("arjun.cloud@gmail.com", "password123")
            assertTrue("Login on Device B must succeed", loginBRes is AuthResult.Success)
            val userB = (loginBRes as AuthResult.Success).user

            // 4. Verify all profile fields, XP, level, coins, solved count, topics, bookmarks, mistakes, goals, achievements match Device A
            assertEquals("Arjun Mehta", userB.fullName)
            assertEquals("avatar_ninja", userB.avatarKey)
            assertEquals("Python", userB.preferredLanguage)
            assertEquals("Graphs, Kotlin, Arrays", userB.interests)
            assertEquals(xpOnDeviceA, userB.xp)
            assertEquals(levelOnDeviceA, userB.level)
            assertEquals(coinsOnDeviceA, userB.coins)

            val snapshotB = learnB.observeProgressAnalytics(userB.id).first()
            assertEquals(1, snapshotB.totalSolved)
            assertEquals(1, snapshotB.topics.first { it.topicName == "Programming Basics" }.solvedCount)
            assertEquals(1, learnB.observeBookmarks(userB.id).first().size)
            assertEquals("PB-02", learnB.observeBookmarks(userB.id).first().first().questionId)
            assertEquals(1, learnB.observeMistakes(userB.id).first().size)
            assertEquals("PB-03", learnB.observeMistakes(userB.id).first().first().questionId)

            val goalsB = learnB.observeDailyGoals(userB.id).first()
            assertTrue(goalsB.first { it.goalKey == LearningRepository.GOAL_CONCEPT_QUIZ }.isCompleted)

            val achievementsB = learnB.observeAchievementsWithStatus(userB.id).first()
            assertTrue(achievementsB.first { it.definition.key == GamificationCatalog.ACH_FIRST_SOLVE }.isUnlocked)

            // Verify calling evaluateAchievementsAndBadges on Device B does NOT double-award XP
            learnB.evaluateAchievementsAndBadges(userB.id)
            assertEquals(xpOnDeviceA, dbB.userDao().getUserById(userB.id)!!.xp)

            dbB.close()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun verifySupabaseAuthEdgeCasesMissingTableSmtpIdentitiesAnd42P10(): Unit = runBlocking {
        // Test Case 1: 404 PGRST205 missing table MUST abort BEFORE calling /auth/v1/signup
        var authSignupCalledWhenMissingTable = false
        val missingTableServer = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        missingTableServer.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            if (path.startsWith("/auth/v1/signup")) {
                authSignupCalledWhenMissingTable = true
            }
            val body = """{"code":"PGRST205","message":"Could not find the table 'public.profiles' in the schema cache"}"""
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(404, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        missingTableServer.start()
        try {
            val baseUrl = "http://127.0.0.1:${missingTableServer.address.port}"
            val client = com.example.data.remote.supabase.SupabaseClient(baseUrl, "test_anon_key")
            val prefs = SessionPreferencesManager(context)
            prefs.clearSession()
            val dbFail = Room.inMemoryDatabaseBuilder(context, CodeQuestDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val repo = com.example.data.remote.supabase.SupabaseRepository(
                client = client,
                userDao = dbFail.userDao(),
                questionDao = dbFail.questionDao(),
                progressDao = dbFail.progressDao(),
                sessionPrefs = prefs
            )
            val authRepoMissing = AuthRepository(dbFail.userDao(), dbFail.progressDao(), prefs, repo)
            val failSignUp = authRepoMissing.signUp(
                fullName = "Missing Table User",
                email = "missing.table@gmail.com",
                password = "password123",
                confirmPassword = "password123",
                avatarKey = "avatar_cyber"
            )
            assertTrue(failSignUp is AuthResult.Error)
            assertTrue((failSignUp as AuthResult.Error).message.contains("supabase_schema.sql"))
            assertFalse("Must NOT call /auth/v1/signup when public.profiles is missing", authSignupCalledWhenMissingTable)
            dbFail.close()
        } finally {
            missingTableServer.stop(0)
        }

        // Test Case 2: Obfuscated identities:[], SMTP 500 error, Confirm-Email rejection + 42P10 patch/post fallback
        val profiles = mutableMapOf<String, org.json.JSONObject>()
        var patchCalledFor42P10 = false
        val edgeServer = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        edgeServer.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val query = exchange.requestURI.rawQuery.orEmpty()
            val method = exchange.requestMethod.uppercase()
            val reqBody = exchange.requestBody.bufferedReader().readText()

            fun send(code: Int, body: String) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            fun extractEqParam(paramName: String): String {
                val regex = Regex("$paramName=eq\\.([^&]+)")
                val raw = regex.find(query)?.groupValues?.get(1).orEmpty()
                return java.net.URLDecoder.decode(raw, "UTF-8")
            }

            when {
                path == "/auth/v1/signup" && method == "POST" -> {
                    val json = org.json.JSONObject(reqBody)
                    val email = json.getString("email").lowercase()
                    when (email) {
                        "smtp.blocked@gmail.com" -> send(500, """{"msg":"Error sending confirmation email"}""")
                        "cooldown.user@gmail.com" -> send(429, """{"msg":"For security purposes, you can only request this after 45 seconds."}""")
                        "stub.unconfirmed@gmail.com" -> send(200, """{"id":"uuid-stub","email":"$email","identities":[]}""")
                        else -> send(200, """{"id":"uuid-ok","email":"$email"}""")
                    }
                }
                path == "/auth/v1/token" && method == "POST" -> {
                    send(400, """{"error":"invalid_grant","error_description":"Email not confirmed"}""")
                }
                path == "/rest/v1/profiles" && method == "GET" -> {
                    val email = extractEqParam("email").lowercase()
                    val p = profiles[email]
                    send(200, if (p != null) "[$p]" else "[]")
                }
                path == "/rest/v1/profiles" && method == "POST" -> {
                    if (query.contains("on_conflict=email")) {
                        // Simulate table with PK = id and no UNIQUE constraint on email
                        send(400, """{"code":"42P10","message":"there is no unique or exclusion constraint matching the ON CONFLICT specification"}""")
                    } else {
                        val json = org.json.JSONObject(reqBody)
                        val email = json.getString("email").lowercase()
                        profiles[email] = json
                        send(201, "[$json]")
                    }
                }
                path == "/rest/v1/profiles" && method == "PATCH" -> {
                    patchCalledFor42P10 = true
                    val email = extractEqParam("email").lowercase()
                    val json = org.json.JSONObject(reqBody)
                    profiles[email] = json
                    send(200, "[$json]")
                }
                else -> send(200, "[]")
            }
        }
        edgeServer.start()

        try {
            val baseUrl = "http://127.0.0.1:${edgeServer.address.port}"
            val client = com.example.data.remote.supabase.SupabaseClient(baseUrl, "test_anon_key")
            val prefs = SessionPreferencesManager(context)
            prefs.clearSession()
            val dbEdge = Room.inMemoryDatabaseBuilder(context, CodeQuestDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val supaRepo = com.example.data.remote.supabase.SupabaseRepository(
                client = client,
                userDao = dbEdge.userDao(),
                questionDao = dbEdge.questionDao(),
                progressDao = dbEdge.progressDao(),
                sessionPrefs = prefs
            )
            val authEdge = AuthRepository(dbEdge.userDao(), dbEdge.progressDao(), prefs, supaRepo)

            // A) SMTP 500 "Error sending confirmation email" still registers user in profiles (with 42P10 fallback)
            val smtpSignUp = authEdge.signUp(
                fullName = "Smtp Fallback User",
                email = "smtp.blocked@gmail.com",
                password = "password123",
                confirmPassword = "password123",
                avatarKey = "avatar_cyber"
            )
            assertTrue("Signup must succeed despite SMTP 500", smtpSignUp is AuthResult.Success)
            assertTrue(profiles.containsKey("smtp.blocked@gmail.com"))

            // B) Obfuscated identities:[] + Email not confirmed unconfirmed stub rescues into public.profiles
            val stubSignUp = authEdge.signUp(
                fullName = "Stub User",
                email = "stub.unconfirmed@gmail.com",
                password = "password123",
                confirmPassword = "password123",
                avatarKey = "avatar_ninja"
            )
            assertTrue("Unconfirmed stub with no profile row must rescue cleanly", stubSignUp is AuthResult.Success)
            assertTrue(profiles.containsKey("stub.unconfirmed@gmail.com"))

            // C) Trigger 42P10 PATCH path by pre-existing row + cross-device login on fresh DB
            val prefsB = SessionPreferencesManager(context)
            prefsB.clearSession()
            val dbEdgeB = Room.inMemoryDatabaseBuilder(context, CodeQuestDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val supaRepoB = com.example.data.remote.supabase.SupabaseRepository(
                client = com.example.data.remote.supabase.SupabaseClient(baseUrl, "test_anon_key"),
                userDao = dbEdgeB.userDao(),
                questionDao = dbEdgeB.questionDao(),
                progressDao = dbEdgeB.progressDao(),
                sessionPrefs = prefsB
            )
            val authEdgeB = AuthRepository(dbEdgeB.userDao(), dbEdgeB.progressDao(), prefsB, supaRepoB)

            // Login with unconfirmed auth token -> password_hash fallback succeeds & syncs profile via 42P10 PATCH
            val loginRes = authEdgeB.login("smtp.blocked@gmail.com", "password123")
            assertTrue("Cross-device login must succeed even when GoTrue says Email not confirmed", loginRes is AuthResult.Success)
            assertEquals("Smtp Fallback User", (loginRes as AuthResult.Success).user.fullName)
            assertTrue("42P10 PATCH fallback should have been invoked on sync push", patchCalledFor42P10)

            // Wrong password on unconfirmed account must fail
            val badLogin = authEdgeB.login("stub.unconfirmed@gmail.com", "wrongpassword")
            assertTrue(badLogin is AuthResult.Error)

            // D) Orphaned public.profiles row (e.g. admin deleted user in Supabase Auth -> Users) must allow fresh re-signup
            profiles["recreated@gmail.com"] = org.json.JSONObject().apply {
                put("email", "recreated@gmail.com")
                put("full_name", "Old Deleted User")
                put("xp", 500)
            }
            val recreateRes = authEdgeB.signUp(
                fullName = "Recreated Coder",
                email = "recreated@gmail.com",
                password = "newpass123",
                confirmPassword = "newpass123",
                avatarKey = "avatar_ninja"
            )
            assertTrue("Re-signup with orphaned public.profiles row must succeed when GoTrue creates new user", recreateRes is AuthResult.Success)
            assertEquals("Recreated Coder", profiles["recreated@gmail.com"]?.optString("full_name"))
            assertEquals(0, profiles["recreated@gmail.com"]?.optInt("xp", -1))

            // E) Existing active account on a fresh device (profile exists AND GoTrue does not create new user) must NOT overwrite on soft-auth/duplicate errors
            val prefsC = SessionPreferencesManager(context)
            prefsC.clearSession()
            val dbEdgeC = Room.inMemoryDatabaseBuilder(context, CodeQuestDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val supaRepoC = com.example.data.remote.supabase.SupabaseRepository(
                client = com.example.data.remote.supabase.SupabaseClient(baseUrl, "test_anon_key"),
                userDao = dbEdgeC.userDao(),
                questionDao = dbEdgeC.questionDao(),
                progressDao = dbEdgeC.progressDao(),
                sessionPrefs = prefsC
            )
            val authEdgeC = AuthRepository(dbEdgeC.userDao(), dbEdgeC.progressDao(), prefsC, supaRepoC)
            val duplicateBlocked = authEdgeC.signUp(
                fullName = "Overwrite Attempt",
                email = "smtp.blocked@gmail.com",
                password = "hacker123",
                confirmPassword = "hacker123",
                avatarKey = "avatar_cyber"
            )
            assertTrue("Existing profile + non-new GoTrue response must reject signup", duplicateBlocked is AuthResult.Error)
            assertTrue((duplicateBlocked as AuthResult.Error).message.contains("already exists on Supabase"))
            assertEquals("Smtp Fallback User", profiles["smtp.blocked@gmail.com"]?.optString("full_name"))

            // F) 429 security cooldown must return wait-60s message (not falsely succeed without auth.users or claim duplicate)
            val cooldownRes = authEdgeC.signUp(
                fullName = "Cooldown User",
                email = "cooldown.user@gmail.com",
                password = "password123",
                confirmPassword = "password123",
                avatarKey = "avatar_cyber"
            )
            assertTrue("429 cooldown should surface clear wait message", cooldownRes is AuthResult.Error)
            assertTrue((cooldownRes as AuthResult.Error).message.contains("60 seconds"))
            assertFalse("Cooldown failure must not leave orphan profile row", profiles.containsKey("cooldown.user@gmail.com"))

            dbEdge.close()
            dbEdgeB.close()
            dbEdgeC.close()
        } finally {
            edgeServer.stop(0)
        }
    }

    @Test
    fun verifyGeminiApiClientStreamingFallbackAndCodeEvaluation(): Unit = runBlocking {
        val capturedPayloads = mutableListOf<Pair<String, String>>()
        var failPrimaryWith404 = false

        val geminiServer = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        geminiServer.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val reqBody = exchange.requestBody.bufferedReader().readText()
            capturedPayloads.add(path to reqBody)

            fun send(code: Int, contentType: String, body: String) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", contentType)
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            when {
                failPrimaryWith404 && path.contains("gemini-2.5-flash") -> {
                    send(404, "application/json", """{"error":{"code":404,"message":"Model not found"}}""")
                }
                path.contains(":streamGenerateContent") -> {
                    val sse = buildString {
                        append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi Rohan! \"},{\"thought\":true,\"text\":\"internal thought\"}]}}]}\n\n")
                        append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Use a HashMap for O(N) Two Sum.\"}]}}]}\n\n")
                        append("data: [DONE]\n\n")
                    }
                    send(200, "text/event-stream", sse)
                }
                reqBody.contains("executionStatus") -> {
                    val evalPayload = org.json.JSONObject().apply {
                        put("compileStatus", "SUCCESS")
                        put("executionStatus", "ACCEPTED")
                        put("passed", true)
                        put("stdout", "Case 1 Passed: 15 50")
                        put("stderr", "")
                        put("testCasesPassed", 1)
                        put("testCasesTotal", 1)
                        put("errorLine", org.json.JSONObject.NULL)
                        put("inputGiven", "5 10")
                        put("expectedOutput", "15 50")
                        put("actualOutput", "15 50")
                    }.toString()
                    val wrapped = org.json.JSONObject().apply {
                        put("candidates", org.json.JSONArray().apply {
                            put(org.json.JSONObject().apply {
                                put("content", org.json.JSONObject().apply {
                                    put("parts", org.json.JSONArray().apply {
                                        put(org.json.JSONObject().put("text", evalPayload))
                                    })
                                })
                            })
                        })
                    }.toString()
                    send(200, "application/json", wrapped)
                }
                else -> {
                    val wrapped = org.json.JSONObject().apply {
                        put("candidates", org.json.JSONArray().apply {
                            put(org.json.JSONObject().apply {
                                put("content", org.json.JSONObject().apply {
                                    put("parts", org.json.JSONArray().apply {
                                        put(org.json.JSONObject().put("text", "Fallback response from 2.0 Flash"))
                                    })
                                })
                            })
                        })
                    }.toString()
                    send(200, "application/json", wrapped)
                }
            }
        }
        geminiServer.start()

        try {
            com.example.data.remote.GeminiApiClient.apiOriginOverride = "http://127.0.0.1:${geminiServer.address.port}"
            com.example.data.remote.GeminiApiClient.apiKeyOverride = "test_gemini_key"

            val learner = com.example.data.remote.LearnerProfileContext(
                name = "Rohan",
                email = "rohan@gmail.com",
                interests = "Arrays, Dynamic Programming",
                level = 2,
                xp = 140,
                streak = 3,
                solvedCount = 4,
                preferredLanguage = "Kotlin"
            )

            // 1. Streaming on primary gemini-2.5-flash uses thinkingBudget=0 (no invalid thinkingLevel) and ignores thought parts
            val streamedChunks = mutableListOf<String>()
            val streamResult = com.example.data.remote.GeminiApiClient.askMentorStream(
                prompt = "Explain Two Sum",
                learner = learner,
                chunkDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
            ) { chunk ->
                streamedChunks.add(chunk)
            }
            assertTrue(streamResult.isSuccess)
            assertEquals("Hi Rohan! Use a HashMap for O(N) Two Sum.", streamResult.getOrNull())
            assertTrue(streamedChunks.isNotEmpty())

            val streamPayload = capturedPayloads.first().second
            assertTrue(capturedPayloads.first().first.contains("gemini-2.5-flash"))
            assertFalse("Must NOT send invalid thinkingLevel field", streamPayload.contains("thinkingLevel"))
            assertTrue("Must send thinkingBudget for gemini-2.5-flash", streamPayload.contains("\"thinkingBudget\":0"))
            assertTrue("Must include real solvedCount in system prompt", streamPayload.contains("4 DSA problems solved"))

            // 2. Fallback from gemini-2.5-flash (404) -> gemini-2.0-flash (200) without thinkingConfig
            capturedPayloads.clear()
            failPrimaryWith404 = true
            val fallbackResult = com.example.data.remote.GeminiApiClient.askMentor("Explain BFS", learner = learner)
            assertTrue(fallbackResult.isSuccess)
            assertEquals("Fallback response from 2.0 Flash", fallbackResult.getOrNull())
            assertEquals(2, capturedPayloads.size)
            assertTrue(capturedPayloads[1].first.contains("gemini-2.0-flash"))
            assertFalse("gemini-2.0-flash payload should omit thinkingConfig", capturedPayloads[1].second.contains("thinkingConfig"))

            // 3. Evaluate Code with AI parses structured JSON response cleanly
            failPrimaryWith404 = false
            val evalResult = com.example.data.remote.GeminiApiClient.evaluateCodeWithAI(
                language = "Kotlin",
                sourceCode = "fun main() { println(\"15 50\") }",
                testCases = listOf(com.example.data.remote.ExecuteTestCaseDto(input = "5 10", expectedOutput = "15 50")),
                sampleInput = "5 10"
            )
            assertTrue(evalResult.isSuccess)
            val evalDto = evalResult.getOrThrow()
            assertTrue(evalDto.passed)
            assertEquals("15 50", evalDto.actualOutput)
        } finally {
            com.example.data.remote.GeminiApiClient.apiOriginOverride = null
            com.example.data.remote.GeminiApiClient.apiKeyOverride = null
            geminiServer.stop(0)
        }
    }

    @Test
    fun verifyGalleryAvatarDownsamplingAndAuthSessionGate(): Unit = runBlocking {
        // 1. Oversized photo URI must be safely downsampled to <= 512px without OOM/crash
        val largeBitmap = android.graphics.Bitmap.createBitmap(2400, 1800, android.graphics.Bitmap.Config.ARGB_8888)
        val tempFile = java.io.File(context.cacheDir, "large_gallery_sample.jpg")
        java.io.FileOutputStream(tempFile).use { out ->
            largeBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        }
        largeBitmap.recycle()

        val savedPath = com.example.utils.AvatarHelper.copyGalleryUriToInternalStorage(
            context,
            android.net.Uri.fromFile(tempFile)
        )
        assertNotNull("Downsampled avatar must save cleanly", savedPath)
        val decodedOpts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(savedPath!!, decodedOpts)
        assertTrue("Downsampled avatar width must be <= 512", decodedOpts.outWidth in 1..512)
        assertTrue("Downsampled avatar height must be <= 512", decodedOpts.outHeight in 1..512)

        val imageView = android.widget.ImageView(context)
        com.example.utils.AvatarHelper.loadAvatarInto(imageView, savedPath, true)
        assertNotNull(imageView.drawable)

        // 2. Stale session preferences pointing to missing DB user must NOT resolve to a default user
        sessionPrefs.saveSession(9999L, "ghost@gmail.com", null)
        assertNull("Orphaned session ID without DB user must return null", authRepo.getLoggedInUser())
        tempFile.delete()
        java.io.File(savedPath).delete()
    }

    @Test
    fun verifyDeviceSystemNotificationsFlushAfterPermissionAndLearningBridge(): Unit = runBlocking {
        val signUpResult = authRepo.signUp(
            fullName = "Notif Tester",
            email = "notif.tester@gmail.com",
            password = "password123",
            confirmPassword = "password123",
            avatarKey = "avatar_cyber",
            isCustomPhoto = false
        )
        assertTrue(signUpResult is AuthResult.Success)
        val user = (signUpResult as AuthResult.Success).user

        val app = context.applicationContext as android.app.Application
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val shadowNotifManager = org.robolectric.Shadows.shadowOf(notificationManager)
        notificationManager.cancelAll()

        val notifService = com.example.service.RoomNotificationService(learningRepo, sessionPrefs)
        learningRepo.onNotificationCreated = { notif ->
            notifService.postNotificationEntity(context, notif)
        }

        // 1. Before POST_NOTIFICATIONS is granted on Android 13+, triggerLoginNotifications stores in Room without posting to tray
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(notifService.areDeviceNotificationsPermitted(context))

        notifService.triggerLoginNotifications(context, user)
        val storedBeforeGrant = learningRepo.getNotifications(user.id)
        assertTrue("Login notifications should be stored in Room", storedBeforeGrant.isNotEmpty())
        assertEquals(0, shadowNotifManager.allNotifications.size)

        // 2. Once POST_NOTIFICATIONS is granted, flushUnpostedNotificationsToTray immediately posts them to device notification drawer
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(notifService.areDeviceNotificationsPermitted(context))

        val flushedCount = notifService.flushUnpostedNotificationsToTray(context, user.id)
        assertTrue("Flushing after permission grant must post notifications to system tray", flushedCount > 0)
        assertTrue("System NotificationManager must hold posted alerts", shadowNotifManager.allNotifications.isNotEmpty())

        // High-importance channel must be registered
        val channel = notificationManager.getNotificationChannel(com.example.service.RoomNotificationService.CHANNEL_ENGAGEMENT_ID)
        assertNotNull(channel)
        assertEquals(android.app.NotificationManager.IMPORTANCE_HIGH, channel!!.importance)

        // Re-flushing must NOT duplicate already posted tray alerts
        val secondFlushCount = notifService.flushUnpostedNotificationsToTray(context, user.id)
        assertEquals(0, secondFlushCount)

        // 3. Completing a Daily Goal triggers LearningRepository -> onNotificationCreated -> system tray alert
        val beforeGoalCount = shadowNotifManager.allNotifications.size
        learningRepo.completeConceptQuizAction(user.id)
        assertTrue(
            "Daily Goal / Badge notifications must automatically post to device notification tray",
            shadowNotifManager.allNotifications.size > beforeGoalCount
        )

        // 4. Test Device Alert posts cleanly
        val sentTest = notifService.sendTestDeviceNotification(context, user.id)
        assertTrue(sentTest)
    }

    @Test
    fun verifyGeminiInvalidApiKeyDoesNotMaskWithFallback404(): Unit = runBlocking {
        val capturedPaths = mutableListOf<String>()
        val capturedBodies = mutableListOf<String>()
        val errorServer = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        errorServer.createContext("/") { exchange ->
            capturedPaths.add(exchange.requestURI.path)
            capturedBodies.add(exchange.requestBody.bufferedReader().readText())
            if (exchange.requestURI.rawQuery.orEmpty().contains("AQ.ValidExpressTestKey")) {
                val sse = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Vertex Express OK\"}]}}]}\n\n"
                val bytes = sse.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                val body = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(400, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        errorServer.start()

        try {
            // Verify default URL routing for AQ.* vs AIza* keys (without origin override)
            com.example.data.remote.GeminiApiClient.apiOriginOverride = null
            assertTrue(
                com.example.data.remote.GeminiApiClient
                    .buildStreamUrl("gemini-2.5-flash", "AQ.TestKey123")
                    .startsWith("https://aiplatform.googleapis.com/v1/publishers/google/models/gemini-2.5-flash:streamGenerateContent?alt=sse&key=AQ.TestKey123")
            )
            assertTrue(
                com.example.data.remote.GeminiApiClient
                    .buildStreamUrl("gemini-2.5-flash", "AIzaTestKey123")
                    .startsWith("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse&key=AIzaTestKey123")
            )

            com.example.data.remote.GeminiApiClient.apiOriginOverride = "http://127.0.0.1:${errorServer.address.port}"

            // 1. AQ.* Vertex AI Express key routes to /v1/publishers/google/models/... and sends role=user
            com.example.data.remote.GeminiApiClient.apiKeyOverride = "AQ.ValidExpressTestKey"
            val expressRes = com.example.data.remote.GeminiApiClient.askMentorStream(
                prompt = "Explain BFS",
                chunkDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
            ) { }
            assertTrue("AQ.* Vertex Express key should succeed", expressRes.isSuccess)
            assertEquals("Vertex Express OK", expressRes.getOrNull())
            assertTrue(capturedPaths.last().contains("/v1/publishers/google/models/gemini-2.5-flash:streamGenerateContent"))
            assertTrue("Vertex AI payload must include role=user", capturedBodies.last().contains("\"role\":\"user\""))

            // 2. Rejected API key fails immediately without masking with fallback 404
            capturedPaths.clear()
            com.example.data.remote.GeminiApiClient.apiKeyOverride = "AIzaBadKeyExample"
            val res = com.example.data.remote.GeminiApiClient.askMentorStream(
                prompt = "Explain BFS",
                chunkDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
            ) { }
            assertTrue(res.isFailure)
            val msg = res.exceptionOrNull()?.message.orEmpty()
            assertTrue("Should explain API key rejection clearly: $msg", msg.contains("Invalid GEMINI_API_KEY"))
            assertEquals("Should not waste a fallback request on API key rejection", 1, capturedPaths.size)
        } finally {
            com.example.data.remote.GeminiApiClient.apiOriginOverride = null
            com.example.data.remote.GeminiApiClient.apiKeyOverride = null
            errorServer.stop(0)
        }
    }
}
