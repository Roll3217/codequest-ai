package com.example.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.CodeQuestApp
import com.example.R
import com.example.databinding.ActivityOnboardingBinding
import com.example.databinding.ItemOnboardingPageBinding

data class OnboardingPageItem(
    val badge: String,
    val title: String,
    val description: String,
    val codeSnippet: String,
    @DrawableRes val iconRes: Int
)

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private val pages by lazy {
        listOf(
            OnboardingPageItem(
                badge = getString(R.string.onboard_badge_1),
                title = getString(R.string.onboard_title_1),
                description = getString(R.string.onboard_desc_1),
                codeSnippet = "fun verifySolution(passedAllTests: Boolean): Int {\n    return if (passedAllTests) +1 else 0 // Authentic progress\n}",
                iconRes = R.drawable.ic_nav_practice
            ),
            OnboardingPageItem(
                badge = getString(R.string.onboard_badge_2),
                title = getString(R.string.onboard_title_2),
                description = getString(R.string.onboard_desc_2),
                codeSnippet = "// AI Mentor Structured Output:\n// • Explanation & Approach\n// • Time: O(N log N) | Space: O(1)\n// • Common Edge-Case Pitfalls",
                iconRes = R.drawable.ic_nav_ai
            ),
            OnboardingPageItem(
                badge = getString(R.string.onboard_badge_3),
                title = getString(R.string.onboard_title_3),
                description = getString(R.string.onboard_desc_3),
                codeSnippet = "if (dailyGoalsCompleted >= 3) {\n    streak.incrementQualifyingDay()\n    user.awardXpAndCheckLevelUp()\n}",
                iconRes = R.drawable.ic_streak_flame
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as CodeQuestApp
        app.sessionPreferences.applySavedTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.viewpagerOnboarding.adapter = OnboardingPagerAdapter(pages)

        binding.viewpagerOnboarding.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateOnboardingUi(position)
            }
        })

        binding.btnSkipOnboarding.setOnClickListener {
            completeOnboardingAndProceed(app)
        }

        binding.btnOnboardingBack.setOnClickListener {
            val current = binding.viewpagerOnboarding.currentItem
            if (current > 0) {
                binding.viewpagerOnboarding.currentItem = current - 1
            }
        }

        binding.btnOnboardingNext.setOnClickListener {
            val current = binding.viewpagerOnboarding.currentItem
            if (current < pages.lastIndex) {
                binding.viewpagerOnboarding.currentItem = current + 1
            } else {
                completeOnboardingAndProceed(app)
            }
        }
    }

    private fun updateOnboardingUi(position: Int) {
        binding.tvOnboardingStep.text = "STEP ${position + 1} OF ${pages.size}"
        binding.btnOnboardingBack.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
        binding.btnOnboardingNext.text = if (position == pages.lastIndex) {
            getString(R.string.btn_start_coding_now)
        } else {
            getString(R.string.btn_next)
        }

        val dots = listOf(binding.dot1, binding.dot2, binding.dot3)
        dots.forEachIndexed { index, view ->
            val params = view.layoutParams
            val density = resources.displayMetrics.density
            params.width = ((if (index == position) 28 else 10) * density).toInt()
            view.layoutParams = params
            view.setBackgroundResource(
                if (index == position) R.drawable.bg_pill_purple else R.drawable.bg_pill_cyan
            )
        }
    }

    private fun completeOnboardingAndProceed(app: CodeQuestApp) {
        app.sessionPreferences.hasCompletedOnboarding = true
        startActivity(Intent(this, SignupActivity::class.java))
        finish()
    }

    private class OnboardingPagerAdapter(
        private val items: List<OnboardingPageItem>
    ) : RecyclerView.Adapter<OnboardingPagerAdapter.PageViewHolder>() {

        inner class PageViewHolder(val itemBinding: ItemOnboardingPageBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val binding = ItemOnboardingPageBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return PageViewHolder(binding)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            val item = items[position]
            holder.itemBinding.tvOnboardingBadge.text = item.badge
            holder.itemBinding.tvOnboardingTitle.text = item.title
            holder.itemBinding.tvOnboardingDesc.text = item.description
            holder.itemBinding.tvOnboardingCodePreview.text = item.codeSnippet
            holder.itemBinding.ivOnboardingIcon.setImageResource(item.iconRes)
        }

        override fun getItemCount(): Int = items.size
    }
}
