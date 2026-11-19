package com.example.ui.practice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.CodeQuestApp
import com.example.R
import com.example.databinding.ActivityProblemDetailBinding
import com.example.model.QuestionWithStatus
import com.example.ui.coding.CodingLabActivity
import kotlinx.coroutines.launch

class ProblemDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProblemDetailBinding
    private var currentQuestionWithStatus: QuestionWithStatus? = null
    private var hintsVisible: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as CodeQuestApp
        app.sessionPreferences.applySavedTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityProblemDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val questionId = intent.getStringExtra(EXTRA_QUESTION_ID) ?: "PB-01"
        val userId = app.sessionPreferences.loggedInUserId

        binding.btnDetailBack.setOnClickListener {
            finish()
        }

        binding.btnToggleHints.setOnClickListener {
            hintsVisible = !hintsVisible
            binding.layoutHintsApproachBody.visibility =
                if (hintsVisible) View.VISIBLE else View.GONE
            binding.btnToggleHints.text =
                if (hintsVisible) "Hide Details" else "Show Details"
        }

        binding.btnDetailBookmark.setOnClickListener {
            val qStatus = currentQuestionWithStatus ?: return@setOnClickListener
            if (userId <= 0L) return@setOnClickListener
            lifecycleScope.launch {
                val nowBookmarked = app.learningRepository.toggleBookmark(userId, qStatus.question)
                Toast.makeText(
                    this@ProblemDetailActivity,
                    if (nowBookmarked) "Added to Bookmarks" else "Removed from Bookmarks",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.btnDetailMistake.setOnClickListener {
            val qStatus = currentQuestionWithStatus ?: return@setOnClickListener
            if (userId <= 0L) return@setOnClickListener
            lifecycleScope.launch {
                val nowInMistakes = app.learningRepository.toggleMistake(userId, qStatus.question)
                Toast.makeText(
                    this@ProblemDetailActivity,
                    if (nowInMistakes) "Saved to Mistakes Notebook" else "Removed from Mistakes Notebook",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.btnMarkMistakeReviewed.setOnClickListener {
            if (userId <= 0L) return@setOnClickListener
            // Expand the approach/complexity section and complete the real "Review One Mistake" Daily Goal
            if (!hintsVisible) {
                hintsVisible = true
                binding.layoutHintsApproachBody.visibility = View.VISIBLE
                binding.btnToggleHints.text = "Hide Details"
            }
            lifecycleScope.launch {
                val qId = currentQuestionWithStatus?.question?.id
                val newlyCompleted = app.learningRepository.completeMistakeReviewAction(userId, qId)
                val msg = if (newlyCompleted) {
                    "Mistake Reviewed! 'Review One Mistake' Daily Goal completed (+15 XP) & Bug Hunter Badge checked."
                } else {
                    "Mistake Reviewed! Review count updated in your Mistakes Notebook."
                }
                Toast.makeText(this@ProblemDetailActivity, msg, Toast.LENGTH_LONG).show()
            }
        }

        binding.btnDetailStartCoding.setOnClickListener {
            val qStatus = currentQuestionWithStatus ?: return@setOnClickListener
            startActivity(CodingLabActivity.createIntent(this, qStatus.question.id))
        }

        // Observe question and user status (Important: Opening a problem NEVER increases solved count or topic progress)
        lifecycleScope.launch {
            app.learningRepository.ensureInitializedForUser(userId)
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.learningRepository.observeQuestionWithStatusById(userId, questionId)
                    .collect { qWithStatus ->
                        if (qWithStatus != null) {
                            currentQuestionWithStatus = qWithStatus
                            bindQuestionDetails(qWithStatus)
                        }
                    }
            }
        }
    }

    private fun bindQuestionDetails(item: QuestionWithStatus) {
        val q = item.question
        binding.tvDetailQuestionId.text = q.id
        binding.tvDetailTitle.text = q.title
        binding.tvDetailTopic.text = q.topic
        binding.tvDetailSubtopic.text = "Subtopic: ${q.subtopic} • Tags: ${q.tagsCsv}"
        binding.tvDetailDescription.text = q.description
        binding.tvDetailExpectedInput.text = q.expectedInput
        binding.tvDetailExpectedOutput.text = q.expectedOutput
        binding.tvDetailExamples.text = q.examplesJson
        binding.tvDetailConstraints.text = q.constraintsText
        binding.tvDetailHints.text = q.hintsJson
        binding.tvDetailApproach.text = q.approachText
        binding.tvDetailComplexity.text = q.complexityText

        // Difficulty badge styling
        binding.tvDetailDifficulty.text = q.difficulty
        when (q.difficulty) {
            "Easy" -> {
                binding.tvDetailDifficulty.setBackgroundResource(R.drawable.bg_badge_easy)
                binding.tvDetailDifficulty.setTextColor(ContextCompat.getColor(this, R.color.cq_easy))
            }
            "Medium" -> {
                binding.tvDetailDifficulty.setBackgroundResource(R.drawable.bg_badge_medium)
                binding.tvDetailDifficulty.setTextColor(ContextCompat.getColor(this, R.color.cq_medium))
            }
            else -> {
                binding.tvDetailDifficulty.setBackgroundResource(R.drawable.bg_badge_hard)
                binding.tvDetailDifficulty.setTextColor(ContextCompat.getColor(this, R.color.cq_hard))
            }
        }

        // Solved status badge
        if (item.isSolved) {
            setSolvedBadge("✓ Solved", R.color.cq_easy)
        } else {
            setSolvedBadge("Unsolved", R.color.cq_text_muted)
        }

        // Bookmark button state
        binding.btnDetailBookmark.text = if (item.isBookmarked) "Bookmarked" else "Bookmark"

        // Mistake button & review banner state
        binding.btnDetailMistake.text = if (item.isInMistakes) "In Mistakes" else "Add to Mistakes"
        binding.cardDetailMistakeReview.visibility =
            if (item.isInMistakes) View.VISIBLE else View.GONE
    }

    private fun setSolvedBadge(label: String, colorRes: Int) {
        binding.tvDetailSolvedBadge.text = label
        binding.tvDetailSolvedBadge.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    companion object {
        private const val EXTRA_QUESTION_ID = "extra_question_id"

        fun createIntent(context: Context, questionId: String): Intent {
            return Intent(context, ProblemDetailActivity::class.java).apply {
                putExtra(EXTRA_QUESTION_ID, questionId)
            }
        }
    }
}
