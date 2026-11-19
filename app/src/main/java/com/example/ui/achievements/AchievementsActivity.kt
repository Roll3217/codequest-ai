package com.example.ui.achievements

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.CodeQuestApp
import com.example.R
import com.example.databinding.ActivityAchievementsBinding
import com.example.databinding.ItemAchievementCardBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AchievementsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAchievementsBinding
    private lateinit var adapter: GamificationItemsAdapter

    private var showingBadgesMode: Boolean = false
    private var latestAchievements: List<AchievementWithStatus> = emptyList()
    private var latestBadges: List<BadgeWithStatus> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as CodeQuestApp
        app.sessionPreferences.applySavedTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityAchievementsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val userId = app.sessionPreferences.loggedInUserId
        showingBadgesMode = intent.getBooleanExtra(EXTRA_START_BADGES_TAB, false)

        binding.btnAchBack.setOnClickListener {
            finish()
        }

        adapter = GamificationItemsAdapter()
        binding.rvAchievementsList.layoutManager = LinearLayoutManager(this)
        binding.rvAchievementsList.adapter = adapter

        if (showingBadgesMode) {
            binding.chipModeBadges.isChecked = true
        } else {
            binding.chipModeAchievements.isChecked = true
        }

        binding.chipGroupAchMode.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chip_mode_achievements
            showingBadgesMode = (checkedId == R.id.chip_mode_badges)
            renderCurrentList()
        }

        if (userId > 0L) {
            lifecycleScope.launch {
                app.learningRepository.ensureInitializedForUser(userId)
                app.learningRepository.evaluateAchievementsAndBadges(userId)

                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        app.learningRepository.observeAchievementsWithStatus(userId).collect { list ->
                            latestAchievements = list
                            updateHeaderAndList()
                        }
                    }
                    launch {
                        app.learningRepository.observeBadgesWithStatus(userId).collect { list ->
                            latestBadges = list
                            updateHeaderAndList()
                        }
                    }
                }
            }
        }
    }

    private fun updateHeaderAndList() {
        val achUnlocked = latestAchievements.count { it.isUnlocked }
        val achTotal = latestAchievements.size.coerceAtLeast(12)
        val badgeUnlocked = latestBadges.count { it.isUnlocked }
        val badgeTotal = latestBadges.size.coerceAtLeast(9)

        binding.tvAchHeaderSummary.text =
            "$achUnlocked/$achTotal Achievements • $badgeUnlocked/$badgeTotal Badges Unlocked"
        binding.chipModeAchievements.text = "Achievements ($achUnlocked/$achTotal)"
        binding.chipModeBadges.text = "Badges ($badgeUnlocked/$badgeTotal)"
        renderCurrentList()
    }

    private fun renderCurrentList() {
        if (showingBadgesMode) {
            adapter.submitBadges(latestBadges)
        } else {
            adapter.submitAchievements(latestAchievements)
        }
    }

    private class GamificationItemsAdapter :
        RecyclerView.Adapter<GamificationItemsAdapter.ItemViewHolder>() {

        private var achievementItems: List<AchievementWithStatus> = emptyList()
        private var badgeItems: List<BadgeWithStatus> = emptyList()
        private var isBadgesMode: Boolean = false

        fun submitAchievements(list: List<AchievementWithStatus>) {
            isBadgesMode = false
            achievementItems = list
            notifyDataSetChanged()
        }

        fun submitBadges(list: List<BadgeWithStatus>) {
            isBadgesMode = true
            badgeItems = list
            notifyDataSetChanged()
        }

        inner class ItemViewHolder(val itemBinding: ItemAchievementCardBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
            val b = ItemAchievementCardBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ItemViewHolder(b)
        }

        override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
            val ctx = holder.itemView.context
            val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.US)

            if (!isBadgesMode) {
                val item = achievementItems[position]
                val def = item.definition
                holder.itemBinding.tvAchTitle.text = def.title
                holder.itemBinding.tvAchCategory.text = def.category
                holder.itemBinding.tvAchDescription.text = def.description
                holder.itemBinding.tvAchXpBadge.text = "+${def.xpReward} XP"

                val pct = item.progressPercent
                holder.itemBinding.progressAchBar.max = 100
                holder.itemBinding.progressAchBar.progress = pct

                if (item.isUnlocked) {
                    val dateStr = item.unlockedAt?.let { dateFmt.format(Date(it)) } ?: "Unlocked"
                    holder.itemBinding.tvAchStatusLabel.text = "✓ Unlocked ($dateStr)"
                    holder.itemBinding.tvAchStatusLabel.setTextColor(
                        ContextCompat.getColor(ctx, R.color.cq_easy)
                    )
                    holder.itemBinding.ivAchIcon.alpha = 1.0f
                } else {
                    val clampedVal = item.currentValue.coerceAtMost(def.targetValue)
                    holder.itemBinding.tvAchStatusLabel.text =
                        "Locked ($clampedVal/${def.targetValue})"
                    holder.itemBinding.tvAchStatusLabel.setTextColor(
                        ContextCompat.getColor(ctx, R.color.cq_text_muted)
                    )
                    holder.itemBinding.ivAchIcon.alpha = 0.32f
                }
            } else {
                val item = badgeItems[position]
                val def = item.definition
                holder.itemBinding.tvAchTitle.text = def.title
                holder.itemBinding.tvAchCategory.text = def.subtitle
                holder.itemBinding.tvAchDescription.text = def.requirementText
                holder.itemBinding.tvAchXpBadge.text = if (item.isUnlocked) "EARNED" else "BADGE"

                holder.itemBinding.progressAchBar.max = 100
                holder.itemBinding.progressAchBar.progress = if (item.isUnlocked) 100 else 0

                if (item.isUnlocked) {
                    val dateStr = item.unlockedAt?.let { dateFmt.format(Date(it)) } ?: "Unlocked"
                    holder.itemBinding.tvAchStatusLabel.text = "✓ Badge Unlocked ($dateStr)"
                    holder.itemBinding.tvAchStatusLabel.setTextColor(
                        ContextCompat.getColor(ctx, R.color.cq_easy)
                    )
                    holder.itemBinding.ivAchIcon.alpha = 1.0f
                } else {
                    holder.itemBinding.tvAchStatusLabel.text = "Locked Badge"
                    holder.itemBinding.tvAchStatusLabel.setTextColor(
                        ContextCompat.getColor(ctx, R.color.cq_text_muted)
                    )
                    holder.itemBinding.ivAchIcon.alpha = 0.32f
                }
            }
        }

        override fun getItemCount(): Int =
            if (isBadgesMode) badgeItems.size else achievementItems.size
    }

    companion object {
        private const val EXTRA_START_BADGES_TAB = "extra_start_badges_tab"

        fun createIntent(context: Context, startOnBadgesTab: Boolean = false): Intent {
            return Intent(context, AchievementsActivity::class.java).apply {
                putExtra(EXTRA_START_BADGES_TAB, startOnBadgesTab)
            }
        }
    }
}
