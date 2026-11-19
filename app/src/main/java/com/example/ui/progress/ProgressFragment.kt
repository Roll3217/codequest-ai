package com.example.ui.progress

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
import com.example.databinding.FragmentProgressBinding
import com.example.model.ProgressAnalyticsSnapshot
import com.example.model.TopicProgressEntity
import com.example.ui.achievements.AchievementsActivity
import com.example.ui.achievements.LevelProgressionEngine
import com.example.ui.learning.LearningPathActivity
import com.example.ui.mistakes.MistakesNotebookActivity
import com.example.ui.practice.BookmarksHistoryActivity
import com.example.ui.settings.NotificationsActivity
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProgressFragment : Fragment() {

    private var _binding: FragmentProgressBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProgressBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as CodeQuestApp
        val userId = app.sessionPreferences.loggedInUserId

        binding.btnProgressNotifications.setOnClickListener {
            startActivity(Intent(requireContext(), NotificationsActivity::class.java))
        }
        binding.btnProgressAchievements.setOnClickListener {
            startActivity(AchievementsActivity.createIntent(requireContext(), startOnBadgesTab = false))
        }
        binding.btnProgressMistakes.setOnClickListener {
            startActivity(Intent(requireContext(), MistakesNotebookActivity::class.java))
        }
        binding.btnProgressBookmarksHistory.setOnClickListener {
            startActivity(BookmarksHistoryActivity.createIntent(requireContext(), openHistoryTab = false))
        }
        binding.btnProgressOpenLearningPath.setOnClickListener {
            startActivity(Intent(requireContext(), LearningPathActivity::class.java))
        }
        binding.btnProgressViewFullHistory.setOnClickListener {
            startActivity(BookmarksHistoryActivity.createIntent(requireContext(), openHistoryTab = true))
        }

        if (userId > 0L) {
            viewLifecycleOwner.lifecycleScope.launch {
                app.learningRepository.ensureInitializedForUser(userId)

                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    // 1. Observe User XP, Level, Streak
                    launch {
                        app.authRepository.observeUser(userId).collect { user ->
                            if (user != null && _binding != null) {
                                val rank = LevelProgressionEngine.rankTitleForLevel(user.level)
                                binding.tvProgressXp.text = "${user.xp} XP"
                                binding.tvProgressLevelBanner.text =
                                    "Level ${user.level} $rank • ${user.xp} XP • ${user.currentStreak}d Streak (Best: ${user.longestStreak}d)"
                            }
                        }
                    }

                    // 2. Observe Full Progress Analytics Snapshot
                    launch {
                        app.learningRepository.observeProgressAnalytics(userId).collect { snapshot ->
                            if (_binding != null) {
                                bindAnalyticsSnapshot(snapshot)
                            }
                        }
                    }

                    // 3. Observe Unread Notifications Count
                    launch {
                        app.notificationService.observeUnreadCount(userId).collect { unread ->
                            if (_binding != null) {
                                binding.btnProgressNotifications.text = "Alerts ($unread)"
                            }
                        }
                    }
                }
            }
        }
    }

    private fun bindAnalyticsSnapshot(snapshot: ProgressAnalyticsSnapshot) {
        binding.tvProgressOverallPct.text =
            "${snapshot.overallMasteryPercent}% (${snapshot.totalSolved}/${snapshot.totalTopicCapacity})"
        binding.progressOverallMastery.max = snapshot.totalTopicCapacity
        binding.progressOverallMastery.progress =
            snapshot.totalSolved.coerceIn(0, snapshot.totalTopicCapacity)

        binding.tvProgressSolved.text = snapshot.totalSolved.toString()
        binding.tvProgressAttempted.text = snapshot.uniqueQuestionsAttempted.toString()
        binding.tvProgressAccuracy.text = "${snapshot.accuracyPercent}%"

        // Difficulty Breakdown
        val db = snapshot.difficultyBreakdown
        binding.progressDiffEasy.max = db.easyTotal
        binding.progressDiffEasy.progress = db.easySolved.coerceIn(0, db.easyTotal)
        binding.tvDiffEasyCount.text = "${db.easySolved}/${db.easyTotal}"

        binding.progressDiffMedium.max = db.mediumTotal
        binding.progressDiffMedium.progress = db.mediumSolved.coerceIn(0, db.mediumTotal)
        binding.tvDiffMediumCount.text = "${db.mediumSolved}/${db.mediumTotal}"

        binding.progressDiffHard.max = db.hardTotal
        binding.progressDiffHard.progress = db.hardSolved.coerceIn(0, db.hardTotal)
        binding.tvDiffHardCount.text = "${db.hardSolved}/${db.hardTotal}"

        // Topic-Wise Progress (All 8 Topics starting at 0/10)
        bindTopicsBreakdown(snapshot.topics)

        // Recent Activity / History
        if (snapshot.recentAttempts.isEmpty()) {
            binding.tvProgressHistoryList.text =
                "No submissions recorded yet. Practice problems in Coding Lab to build your activity log."
        } else {
            val dateFmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)
            val lines = snapshot.recentAttempts.take(6).map { att ->
                val icon = if (att.passed) "✓" else "•"
                val timeStr = dateFmt.format(Date(att.timestamp))
                "$icon [$timeStr] ${att.questionId}: ${att.questionTitle.ifBlank { "Problem" }} (${att.language}) — ${att.statusMessage.ifBlank { if (att.passed) "Solved" else "Attempted" }}"
            }
            binding.tvProgressHistoryList.text = lines.joinToString("\n")
        }
    }

    private fun bindTopicsBreakdown(topics: List<TopicProgressEntity>) {
        val container = binding.containerProgressTopics
        container.removeAllViews()
        val ctx = requireContext()
        val density = resources.displayMetrics.density

        topics.forEach { topic ->
            val itemLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val intent = Intent(ctx, MainActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_OPEN_TOPIC_FILTER, topic.topicName)
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                }
            }

            val topRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val nameTv = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = "${topic.topicIndex}. ${topic.topicName}"
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.cq_text_primary))
            }

            val fractionTv = TextView(ctx).apply {
                val clamped = topic.solvedCount.coerceIn(0, topic.maxCount)
                text = "$clamped/${topic.maxCount} (${topic.percentage}%)"
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.cq_cyan_bright))
            }

            topRow.addView(nameTv)
            topRow.addView(fractionTv)

            val bar = LinearProgressIndicator(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (4 * density).toInt()
                }
                max = topic.maxCount
                progress = topic.solvedCount.coerceIn(0, topic.maxCount)
                trackThickness = (6 * density).toInt()
                trackCornerRadius = (4 * density).toInt()
                setIndicatorColor(ContextCompat.getColor(ctx, R.color.cq_primary))
                trackColor = ContextCompat.getColor(ctx, R.color.cq_surface_variant)
            }

            itemLayout.addView(topRow)
            itemLayout.addView(bar)
            container.addView(itemLayout)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
