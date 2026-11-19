package com.example.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.CodeQuestApp
import com.example.R
import com.example.databinding.FragmentProfileBinding
import com.example.model.ThemeMode
import com.example.ui.achievements.AchievementWithStatus
import com.example.ui.achievements.AchievementsActivity
import com.example.ui.achievements.BadgeWithStatus
import com.example.ui.achievements.LevelProgressionEngine
import com.example.ui.auth.LoginActivity
import com.example.ui.mistakes.MistakesNotebookActivity
import com.example.ui.practice.BookmarksHistoryActivity
import com.example.ui.settings.NotificationsActivity
import com.example.ui.settings.SettingsActivity
import com.example.utils.AvatarHelper
import kotlinx.coroutines.launch

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private var latestAchievements: List<AchievementWithStatus> = emptyList()
    private var latestBadges: List<BadgeWithStatus> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as CodeQuestApp
        val userId = app.sessionPreferences.loggedInUserId

        // Bind saved theme mode to RadioGroup
        when (app.sessionPreferences.themeMode) {
            ThemeMode.DARK -> binding.rbThemeDark.isChecked = true
            ThemeMode.LIGHT -> binding.rbThemeLight.isChecked = true
            ThemeMode.SYSTEM -> binding.rbThemeSystem.isChecked = true
        }

        binding.rgThemeMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_theme_dark -> ThemeMode.DARK
                R.id.rb_theme_light -> ThemeMode.LIGHT
                else -> ThemeMode.SYSTEM
            }
            if (app.sessionPreferences.themeMode != mode) {
                app.sessionPreferences.themeMode = mode
            }
        }

        binding.btnProfileOpenAchievements.setOnClickListener {
            startActivity(AchievementsActivity.createIntent(requireContext(), startOnBadgesTab = false))
        }
        binding.btnProfileMistakes.setOnClickListener {
            startActivity(Intent(requireContext(), MistakesNotebookActivity::class.java))
        }
        binding.btnProfileBookmarks.setOnClickListener {
            startActivity(BookmarksHistoryActivity.createIntent(requireContext(), openHistoryTab = false))
        }
        binding.btnProfileNotifications.setOnClickListener {
            startActivity(Intent(requireContext(), NotificationsActivity::class.java))
        }
        binding.btnProfileSettings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        binding.btnProfileSharePublic.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val user = app.authRepository.getLoggedInUser() ?: return@launch
                val usernameSlug = user.email.substringBefore('@')
                val isConfigured = com.example.utils.AppConfig.isPublicProfileUrlConfigured()
                val profileUrl = if (isConfigured) {
                    val base = com.example.utils.AppConfig.publicProfileBaseUrl.trimEnd('/')
                    "$base/$usernameSlug"
                } else null

                val shareText = buildString {
                    append("⚡ ${user.fullName}'s CodeQuest AI Profile\n")
                    append("Level ${user.level} (${LevelProgressionEngine.rankTitleForLevel(user.level)})\n")
                    append("XP: ${user.xp} • Streak: ${user.currentStreak} Days • Coins: ${user.coins}\n")
                    if (profileUrl != null) {
                        append("View Public Profile: $profileUrl\n")
                    } else {
                        append("Master Data Structures & Algorithms on CodeQuest AI!\n")
                    }
                }

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "${user.fullName}'s CodeQuest AI Profile")
                    putExtra(Intent.EXTRA_TEXT, shareText)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Public Profile"))
            }
        }

        if (userId > 0L) {
            viewLifecycleOwner.lifecycleScope.launch {
                app.learningRepository.ensureInitializedForUser(userId)
                app.learningRepository.evaluateAchievementsAndBadges(userId)
                if (app.supabaseRepository.isConfigured()) {
                    launch(kotlinx.coroutines.Dispatchers.IO) {
                        try {
                            app.supabaseRepository.syncUserData(userId)
                        } catch (_: Exception) {}
                    }
                }

                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    // 1. Observe User Identity, Level, XP, Coins & Streak
                    launch {
                        app.authRepository.observeUser(userId).collect { user ->
                            if (user != null && _binding != null) {
                                val rank = LevelProgressionEngine.rankTitleForLevel(user.level)
                                val xpIntoLevel = LevelProgressionEngine.xpIntoCurrentLevel(user.xp)
                                val nextLevel = user.level + 1

                                binding.tvProfileName.text = user.fullName
                                binding.tvProfileEmail.text = "${user.email} • $rank"
                                binding.tvProfileStatsBadge.text =
                                    "LEVEL ${user.level} • ${user.xp} XP • ${user.currentStreak} DAY STREAK"
                                binding.progressProfileLevelXp.max = LevelProgressionEngine.XP_PER_LEVEL
                                binding.progressProfileLevelXp.progress = xpIntoLevel
                                binding.tvProfileXpNextLevel.text =
                                    "$xpIntoLevel / ${LevelProgressionEngine.XP_PER_LEVEL} XP to Level $nextLevel • ${user.coins} Coins"
                                binding.tvProfileStatStreak.text = "${user.currentStreak}d"

                                AvatarHelper.loadAvatarInto(
                                    binding.ivProfileAvatar,
                                    user.avatarKey,
                                    user.isCustomPhoto
                                )
                            }
                        }
                    }

                    // 2. Observe Real Progress Analytics (Solved & Accuracy)
                    launch {
                        app.learningRepository.observeProgressAnalytics(userId).collect { snap ->
                            if (_binding != null) {
                                binding.tvProfileStatSolved.text = snap.totalSolved.toString()
                                binding.tvProfileStatAccuracy.text = "${snap.accuracyPercent}%"
                            }
                        }
                    }

                    // 3. Observe Achievements & Badges
                    launch {
                        app.learningRepository.observeAchievementsWithStatus(userId).collect { list ->
                            latestAchievements = list
                            if (_binding != null) {
                                renderAchievementsAndBadgesShowcase()
                            }
                        }
                    }

                    launch {
                        app.learningRepository.observeBadgesWithStatus(userId).collect { list ->
                            latestBadges = list
                            if (_binding != null) {
                                renderAchievementsAndBadgesShowcase()
                            }
                        }
                    }

                    // 4. Observe Mistakes, Bookmarks & Unread Notifications Counts
                    launch {
                        app.learningRepository.observeMistakes(userId).collect { mistakes ->
                            if (_binding != null) {
                                binding.btnProfileMistakes.text = "Mistakes (${mistakes.size})"
                            }
                        }
                    }

                    launch {
                        app.learningRepository.observeBookmarks(userId).collect { bookmarks ->
                            if (_binding != null) {
                                binding.btnProfileBookmarks.text = "Bookmarks (${bookmarks.size})"
                            }
                        }
                    }

                    launch {
                        app.notificationService.observeUnreadCount(userId).collect { unread ->
                            if (_binding != null) {
                                binding.btnProfileNotifications.text = "Alerts ($unread)"
                            }
                        }
                    }
                }
            }
        }

        binding.btnProfileLogout.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Log Out")
                .setMessage("Are you sure you want to log out of your CodeQuest AI account? You will need to sign in again to access your dashboard.")
                .setPositiveButton("Log Out") { _, _ ->
                    app.authRepository.logout()
                    val intent = Intent(requireContext(), LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    requireActivity().finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun renderAchievementsAndBadgesShowcase() {
        val b = _binding ?: return
        val unlockedAch = latestAchievements.filter { it.isUnlocked }
        val unlockedBadges = latestBadges.filter { it.isUnlocked }

        b.tvProfileAchCount.text =
            "${unlockedAch.size}/${latestAchievements.size.coerceAtLeast(12)} Ach • ${unlockedBadges.size}/${latestBadges.size.coerceAtLeast(9)} Badges"

        val achLines = if (unlockedAch.isEmpty()) {
            "Achievements: None unlocked yet — solve a problem or complete a daily quest!"
        } else {
            "Unlocked Achievements: " + unlockedAch.take(4).joinToString(", ") { "✓ ${it.definition.title}" }
        }

        val badgeLines = if (unlockedBadges.isEmpty()) {
            "Badges: None earned yet — complete a Daily Goal, Concept Quiz, or Mistake Review!"
        } else {
            "Earned Badges: " + unlockedBadges.take(4).joinToString(", ") { "★ ${it.definition.title}" }
        }

        b.tvProfileAchievementsSummary.text = "$achLines\n$badgeLines"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
