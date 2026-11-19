package com.example.ui.mistakes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.CodeQuestApp
import com.example.R
import com.example.databinding.ActivityMistakesNotebookBinding
import com.example.databinding.ItemMistakeCardBinding
import com.example.model.MistakeEntity
import com.example.ui.practice.ProblemDetailActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MistakesNotebookActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMistakesNotebookBinding
    private lateinit var mistakesAdapter: MistakesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as CodeQuestApp
        app.sessionPreferences.applySavedTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityMistakesNotebookBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val userId = app.sessionPreferences.loggedInUserId

        binding.btnMistakesBack.setOnClickListener {
            finish()
        }

        mistakesAdapter = MistakesAdapter(
            onEditNotesClicked = { mistake ->
                showEditNotesDialog(app, userId, mistake)
            },
            onReviewAndRetryClicked = { mistake ->
                lifecycleScope.launch {
                    val newlyCompleted =
                        app.learningRepository.completeMistakeReviewAction(userId, mistake.questionId)
                    if (newlyCompleted) {
                        Toast.makeText(
                            this@MistakesNotebookActivity,
                            "Mistake Reviewed! 'Review One Mistake' Daily Goal completed (+15 XP)!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    startActivity(
                        ProblemDetailActivity.createIntent(
                            this@MistakesNotebookActivity,
                            mistake.questionId
                        )
                    )
                }
            }
        )

        binding.rvMistakesList.layoutManager = LinearLayoutManager(this)
        binding.rvMistakesList.adapter = mistakesAdapter

        if (userId > 0L) {
            lifecycleScope.launch {
                app.learningRepository.ensureInitializedForUser(userId)
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    app.learningRepository.observeMistakes(userId).collect { list ->
                        mistakesAdapter.submitList(list)
                        val totalReviews = list.sumOf { it.reviewCount }
                        binding.tvMistakesSubtitle.text =
                            "${list.size} Recorded Mistakes • $totalReviews Reviews Completed"
                        binding.layoutMistakesEmpty.visibility =
                            if (list.isEmpty()) View.VISIBLE else View.GONE
                        binding.rvMistakesList.visibility =
                            if (list.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
            }
        }
    }

    private fun showEditNotesDialog(app: CodeQuestApp, userId: Long, mistake: MistakeEntity) {
        val input = EditText(this).apply {
            setText(mistake.userNotes)
            hint = "What edge case, time complexity, or syntax detail caused this mistake?"
            setPadding(48, 36, 48, 36)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Notes for ${mistake.questionId}")
            .setView(input)
            .setPositiveButton("Save Notes") { _, _ ->
                val newNotes = input.text?.toString().orEmpty().trim()
                lifecycleScope.launch {
                    app.learningRepository.saveOrUpdateMistakeNotes(
                        userId = userId,
                        questionId = mistake.questionId,
                        newNotes = newNotes
                    )
                    Toast.makeText(
                        this@MistakesNotebookActivity,
                        "Mistake notes updated.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNeutralButton("Remove Mistake") { _, _ ->
                lifecycleScope.launch {
                    app.learningRepository.removeMistakeByQuestionId(userId, mistake.questionId)
                    Toast.makeText(
                        this@MistakesNotebookActivity,
                        "Removed from Mistakes Notebook.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class MistakesAdapter(
        private val onEditNotesClicked: (MistakeEntity) -> Unit,
        private val onReviewAndRetryClicked: (MistakeEntity) -> Unit
    ) : RecyclerView.Adapter<MistakesAdapter.MistakeViewHolder>() {

        private var items: List<MistakeEntity> = emptyList()

        fun submitList(newItems: List<MistakeEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class MistakeViewHolder(val itemBinding: ItemMistakeCardBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MistakeViewHolder {
            val b = ItemMistakeCardBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return MistakeViewHolder(b)
        }

        override fun onBindViewHolder(holder: MistakeViewHolder, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context

            holder.itemBinding.tvMistakeQid.text = item.questionId
            holder.itemBinding.tvMistakeTitle.text = item.questionTitle
            holder.itemBinding.tvMistakeDifficulty.text = item.difficulty
            holder.itemBinding.tvMistakeReviewCount.text = "Reviewed: ${item.reviewCount}x"
            holder.itemBinding.tvMistakeMeta.text =
                "Topic: ${item.topic} • Language: ${item.language}"
            holder.itemBinding.tvMistakeReason.text =
                "Reason: ${item.errorReason.ifBlank { "Flagged for Review" }}"
            holder.itemBinding.tvMistakeNotes.text =
                if (item.userNotes.isNotBlank()) {
                    "Notes: ${item.userNotes}"
                } else {
                    "Notes: Tap 'Edit Notes' to document the tricky edge case or lesson learned."
                }

            when (item.difficulty) {
                "Easy" -> {
                    holder.itemBinding.tvMistakeDifficulty.setBackgroundResource(R.drawable.bg_badge_easy)
                    holder.itemBinding.tvMistakeDifficulty.setTextColor(
                        ContextCompat.getColor(ctx, R.color.cq_easy)
                    )
                }
                "Medium" -> {
                    holder.itemBinding.tvMistakeDifficulty.setBackgroundResource(R.drawable.bg_badge_medium)
                    holder.itemBinding.tvMistakeDifficulty.setTextColor(
                        ContextCompat.getColor(ctx, R.color.cq_medium)
                    )
                }
                else -> {
                    holder.itemBinding.tvMistakeDifficulty.setBackgroundResource(R.drawable.bg_badge_hard)
                    holder.itemBinding.tvMistakeDifficulty.setTextColor(
                        ContextCompat.getColor(ctx, R.color.cq_hard)
                    )
                }
            }

            holder.itemBinding.btnMistakeEditNotes.setOnClickListener {
                onEditNotesClicked(item)
            }
            holder.itemBinding.btnMistakeReviewNow.setOnClickListener {
                onReviewAndRetryClicked(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
