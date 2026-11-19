package com.example.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.CodeQuestApp
import com.example.MainActivity
import com.example.R
import com.example.databinding.FragmentHomeBinding
import com.example.model.AttemptEntity
import com.example.model.DailyChallengeStatus
import com.example.model.DailyGoalEntity
import com.example.model.TopicProgressEntity
import com.example.model.UserEntity
import com.example.ui.learning.LearningPathActivity
import com.example.ui.practice.ProblemDetailActivity
import com.example.utils.AvatarHelper
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var currentDailyChallengeQuestionId: String? = null
    private var latestUser: UserEntity? = null
    private var latestGoalsCompletedCount: Int = 0
    private var latestTotalSolvedCount: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as CodeQuestApp
        val userId = app.sessionPreferences.loggedInUserId

        // Quick Practice Cards (Easy / Medium / Hard) -> Open random problem of that difficulty
        binding.cardQuickEasy.setOnClickListener {
            openRandomProblemByDifficulty(app, "Easy")
        }
        binding.cardQuickMedium.setOnClickListener {
            openRandomProblemByDifficulty(app, "Medium")
        }
        binding.cardQuickHard.setOnClickListener {
            openRandomProblemByDifficulty(app, "Hard")
        }

        // Daily Challenge Start Button
        binding.btnStartDailyChallenge.setOnClickListener {
            val qId = currentDailyChallengeQuestionId ?: "PB-01"
            startActivity(ProblemDetailActivity.createIntent(requireContext(), qId))
        }

        // Daily Goals Screen Button
        binding.btnOpenDailyGoals.setOnClickListener {
            startActivity(Intent(requireContext(), DailyGoalsActivity::class.java))
        }

        // AI Mentor Card & Button
        binding.btnHomeOpenAi.setOnClickListener {
            (activity as? MainActivity)?.navigateToTab(R.id.nav_ai)
        }
        binding.cardHomeAiMentor.setOnClickListener {
            (activity as? MainActivity)?.navigateToTab(R.id.nav_ai)
        }

        // Topic Learning Path Button
        binding.btnOpenLearningPath.setOnClickListener {
            startActivity(Intent(requireContext(), LearningPathActivity::class.java))
        }

        // Profile header avatar click -> Profile tab
        binding.frameHomeAvatar.setOnClickListener {
            (activity as? MainActivity)?.navigateToTab(R.id.nav_profile)
        }

        binding.btnHomeNotifications.setOnClickListener {
            startActivity(Intent(requireContext(), com.example.ui.settings.NotificationsActivity::class.java))
        }

        binding.tvHomeAchievementsPreview.setOnClickListener {
            startActivity(com.example.ui.achievements.AchievementsActivity.createIntent(requireContext()))
        }

        if (userId > 0L) {
            viewLifecycleOwner.lifecycleScope.launch {
                app.learningRepository.ensureInitializedForUser(userId)
                // Opportunistic background cloud sync
                app.cloudSyncRepository.syncUserData(userId)

                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    // 1. Observe User Profile, XP, Level & Streak
                    launch {
                        app.authRepository.observeUser(userId).collect { user ->
                            if (user != null && _binding != null) {
                                latestUser = user
                                bindUserHeaderAndXp(user)
                                updateAchievementsPreview()
                            }
                        }
                    }

                    // 2. Observe Today's Daily Challenge
                    launch {
                        app.learningRepository.observeDailyChallenge(userId).collect { challenge ->
                            if (challenge != null && _binding != null) {
                                bindDailyChallenge(challenge)
                            }
                        }
                    }

                    // 3. Observe Today's 6 Daily Goals
                    launch {
                        app.learningRepository.observeDailyGoals(userId).collect { goals ->
                            if (_binding != null) {
                                latestGoalsCompletedCount = goals.count { it.isCompleted }
                                bindDailyGoalsChecklist(goals)
                                updateAchievementsPreview()
                            }
                        }
                    }

                    // 4. Observe 8 Topic Learning Paths (0/10 per topic)
                    launch {
                        app.learningRepository.observeTopicProgress(userId).collect { topics ->
                            if (_binding != null) {
                                latestTotalSolvedCount = topics.sumOf { it.solvedCount }
                                bindTopicsPreview(topics)
                                updateAchievementsPreview()
                            }
                        }
                    }

                    // 5. Observe Recent Activity (Attempts)
                    launch {
                        app.learningRepository.observeRecentAttempts(userId).collect { attempts ->
                            if (_binding != null) {
                                bindRecentActivity(attempts)
                            }
                        }
                    }

                    // 6. Observe Unread Notifications Count for Header Bell Badge
                    launch {
                        app.notificationService.observeUnreadCount(userId).collect { unreadCount ->
                            if (_binding != null) {
                                if (unreadCount > 0) {
                                    binding.tvHomeNotifBadge.visibility = View.VISIBLE
                                    binding.tvHomeNotifBadge.text = if (unreadCount > 9) "9+" else unreadCount.toString()
                                } else {
                                    binding.tvHomeNotifBadge.visibility = View.GONE
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openRandomProblemByDifficulty(app: CodeQuestApp, difficulty: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val question = app.learningRepository.getRandomQuestionByDifficulty(difficulty)
            if (question != null && isAdded) {
                startActivity(ProblemDetailActivity.createIntent(requireContext(), question.id))
            }
        }
    }

    private fun bindUserHeaderAndXp(user: UserEntity) {
        binding.tvHomeUserName.text = user.fullName
        binding.tvHomeLevelXp.text = "LVL ${user.level} • ${user.xp} XP"
        binding.tvHomeStreak.text = "${user.currentStreak}d"
        binding.tvHomeLongestStreak.text = "Best: ${user.longestStreak}d"

        val xpIntoCurrentLevel = user.xp % 100
        val nextLevel = user.level + 1
        binding.tvHomeXpLevelBanner.text =
            "Level ${user.level} • $xpIntoCurrentLevel / 100 XP to Level $nextLevel"
        binding.progressHomeXp.max = 100
        binding.progressHomeXp.progress = xpIntoCurrentLevel

        AvatarHelper.loadAvatarInto(
            binding.ivHomeAvatar,
            user.avatarKey,
            user.isCustomPhoto
        )
    }

    private fun bindDailyChallenge(challenge: DailyChallengeStatus) {
        val q = challenge.question
        currentDailyChallengeQuestionId = q.id
        binding.tvDailyChallengeTitle.text = "${q.id}: ${q.title}"
        binding.tvDailyChallengeDifficulty.text = q.difficulty
        binding.tvDailyChallengeXp.text = "+${challenge.xpReward} XP"

        val ctx = requireContext()
        when (q.difficulty) {
            "Easy" -> {
                binding.tvDailyChallengeDifficulty.setBackgroundResource(R.drawable.bg_badge_easy)
                binding.tvDailyChallengeDifficulty.setTextColor(ContextCompat.getColor(ctx, R.color.cq_easy))
            }
            "Medium" -> {
                binding.tvDailyChallengeDifficulty.setBackgroundResource(R.drawable.bg_badge_medium)
                binding.tvDailyChallengeDifficulty.setTextColor(ContextCompat.getColor(ctx, R.color.cq_medium))
            }
            else -> {
                binding.tvDailyChallengeDifficulty.setBackgroundResource(R.drawable.bg_badge_hard)
                binding.tvDailyChallengeDifficulty.setTextColor(ContextCompat.getColor(ctx, R.color.cq_hard))
            }
        }

        if (challenge.isCompleted) {
            binding.tvDailyChallengeStatus.text = "Status: ✓ Completed Today (+${challenge.xpReward} XP)"
            binding.btnStartDailyChallenge.text = "Review Challenge"
        } else {
            binding.tvDailyChallengeStatus.text =
                "Topic: ${q.topic} • Status: Not Solved Yet"
            binding.btnStartDailyChallenge.text = "Start Challenge"
        }
    }

    private fun bindDailyGoalsChecklist(goals: List<DailyGoalEntity>) {
        val completed = goals.count { it.isCompleted }
        val total = goals.size.coerceAtLeast(1)
        binding.tvHomeGoalsCount.text = "$completed/$total Completed"
        binding.progressHomeGoals.max = total
        binding.progressHomeGoals.progress = completed

        val container = binding.containerHomeDailyGoals
        container.removeAllViews()
        val ctx = requireContext()
        val density = resources.displayMetrics.density

        goals.forEach { goal ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            }

            val statusSymbol = if (goal.isCompleted) "✓" else "○"
            val titleView = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = "$statusSymbol  ${goal.title}"
                textSize = 13f
                setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (goal.isCompleted) R.color.cq_easy else R.color.cq_text_secondary
                    )
                )
            }

            val xpView = TextView(ctx).apply {
                text = "+${goal.xpReward} XP"
                textSize = 11f
                setTextColor(ContextCompat.getColor(ctx, R.color.cq_cyan_bright))
            }

            row.addView(titleView)
            row.addView(xpView)
            container.addView(row)
        }
    }

    private fun bindTopicsPreview(topics: List<TopicProgressEntity>) {
        val totalSolved = topics.sumOf { it.solvedCount.coerceAtMost(it.maxCount) }
        val totalMax = topics.sumOf { it.maxCount }.coerceAtLeast(80)
        binding.tvHomeTopicsSummary.text = "${topics.size} Topics • $totalSolved/$totalMax"

        val container = binding.containerHomeTopicsPreview
        container.removeAllViews()
        val ctx = requireContext()
        val density = resources.displayMetrics.density

        topics.forEach { topic ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
            }

            val nameView = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = "${topic.topicIndex}. ${topic.topicName}"
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.cq_text_primary))
            }

            val progressView = TextView(ctx).apply {
                text = "${topic.solvedCount}/${topic.maxCount} (${topic.percentage}%)"
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.cq_cyan_bright))
            }

            row.addView(nameView)
            row.addView(progressView)
            container.addView(row)
        }
    }

    private fun bindRecentActivity(attempts: List<AttemptEntity>) {
        if (attempts.isEmpty()) {
            binding.tvHomeRecentActivity.text =
                "No submissions yet. Pick a problem from Quick Practice or Daily Challenge to begin!"
            return
        }
        val lines = attempts.take(4).map { att ->
            val icon = if (att.passed) "✓" else "•"
            "$icon ${att.questionId} (${att.language}) — ${att.statusMessage.ifBlank { if (att.passed) "Solved" else "Attempted" }}"
        }
        binding.tvHomeRecentActivity.text = lines.joinToString("\n")
    }

    private fun updateAchievementsPreview() {
        val b = _binding ?: return
        val user = latestUser
        val xp = user?.xp ?: 0
        val streak = user?.currentStreak ?: 0

        val firstSolveStr = if (latestTotalSolvedCount >= 1) "✓ Unlocked" else "Locked — Verify 1 solution"
        val goalStarterStr = if (latestGoalsCompletedCount >= 1) "✓ Unlocked" else "Locked — Complete 1 Daily Goal"
        val streak3Str = if (streak >= 3) "✓ Unlocked" else "Locked — Reach 3-day streak ($streak/3)"
        val xp100Str = if (xp >= 100) "✓ Unlocked" else "Locked — Earn 100 XP ($xp/100)"

        b.tvHomeAchievementsPreview.text =
            "• First Solve ($firstSolveStr)\n" +
                "• Daily Goal Starter ($goalStarterStr)\n" +
                "• 3 Day Streak ($streak3Str)\n" +
                "• 100 XP Milestone ($xp100Str)"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
