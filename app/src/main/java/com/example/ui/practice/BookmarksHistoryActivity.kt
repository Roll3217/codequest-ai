package com.example.ui.practice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
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
import com.example.databinding.ActivityBookmarksHistoryBinding
import com.example.databinding.ItemHistoryAttemptBinding
import com.example.model.AttemptEntity
import com.example.model.BookmarkEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BookmarksHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookmarksHistoryBinding
    private lateinit var listAdapter: BookmarksHistoryAdapter

    private var showingHistoryTab: Boolean = false
    private var latestBookmarks: List<BookmarkEntity> = emptyList()
    private var latestHistory: List<AttemptEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as CodeQuestApp
        app.sessionPreferences.applySavedTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityBookmarksHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val userId = app.sessionPreferences.loggedInUserId
        showingHistoryTab = intent.getBooleanExtra(EXTRA_OPEN_HISTORY_TAB, false)

        binding.btnBhBack.setOnClickListener {
            finish()
        }

        listAdapter = BookmarksHistoryAdapter(
            onOpenProblem = { questionId ->
                startActivity(ProblemDetailActivity.createIntent(this, questionId))
            },
            onRemoveBookmark = { questionId ->
                if (userId > 0L) {
                    lifecycleScope.launch {
                        app.learningRepository.removeBookmarkByQuestionId(userId, questionId)
                    }
                }
            }
        )

        binding.rvBhList.layoutManager = LinearLayoutManager(this)
        binding.rvBhList.adapter = listAdapter

        if (showingHistoryTab) {
            binding.chipTabHistory.isChecked = true
        } else {
            binding.chipTabBookmarks.isChecked = true
        }

        binding.chipGroupBhTab.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chip_tab_bookmarks
            showingHistoryTab = (checkedId == R.id.chip_tab_history)
            renderCurrentTab()
        }

        if (userId > 0L) {
            lifecycleScope.launch {
                app.learningRepository.ensureInitializedForUser(userId)
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        app.learningRepository.observeBookmarks(userId).collect { bookmarks ->
                            latestBookmarks = bookmarks
                            updateHeaderAndRender()
                        }
                    }
                    launch {
                        app.learningRepository.observeAllAttempts(userId).collect { attempts ->
                            latestHistory = attempts
                            updateHeaderAndRender()
                        }
                    }
                }
            }
        }
    }

    private fun updateHeaderAndRender() {
        binding.tvBhSubtitle.text =
            "${latestBookmarks.size} Bookmarks • ${latestHistory.size} History Entries"
        binding.chipTabBookmarks.text = "Bookmarked (${latestBookmarks.size})"
        binding.chipTabHistory.text = "Practice History (${latestHistory.size})"
        renderCurrentTab()
    }

    private fun renderCurrentTab() {
        if (!showingHistoryTab) {
            listAdapter.submitBookmarks(latestBookmarks)
            val isEmpty = latestBookmarks.isEmpty()
            binding.layoutBhEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.rvBhList.visibility = if (isEmpty) View.GONE else View.VISIBLE
            binding.tvBhEmptyTitle.text = "No Bookmarked Problems Yet"
            binding.tvBhEmptyDesc.text =
                "Bookmark coding problems from the Practice list or Problem Details screen to save them here."
        } else {
            listAdapter.submitHistory(latestHistory)
            val isEmpty = latestHistory.isEmpty()
            binding.layoutBhEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.rvBhList.visibility = if (isEmpty) View.GONE else View.VISIBLE
            binding.tvBhEmptyTitle.text = "No Practice History Yet"
            binding.tvBhEmptyDesc.text =
                "Problems you attempt or solve in Coding Lab will be recorded here with timestamps and status."
        }
    }

    private class BookmarksHistoryAdapter(
        private val onOpenProblem: (String) -> Unit,
        private val onRemoveBookmark: (String) -> Unit
    ) : RecyclerView.Adapter<BookmarksHistoryAdapter.RowViewHolder>() {

        private var bookmarks: List<BookmarkEntity> = emptyList()
        private var history: List<AttemptEntity> = emptyList()
        private var isHistoryMode: Boolean = false

        fun submitBookmarks(items: List<BookmarkEntity>) {
            isHistoryMode = false
            bookmarks = items
            notifyDataSetChanged()
        }

        fun submitHistory(items: List<AttemptEntity>) {
            isHistoryMode = true
            history = items
            notifyDataSetChanged()
        }

        inner class RowViewHolder(val itemBinding: ItemHistoryAttemptBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
            val b = ItemHistoryAttemptBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return RowViewHolder(b)
        }

        override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
            val ctx = holder.itemView.context
            val dateFmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)

            if (!isHistoryMode) {
                val item = bookmarks[position]
                holder.itemBinding.tvBhItemQid.text = item.questionId
                holder.itemBinding.tvBhItemBadge.text = item.difficulty
                holder.itemBinding.tvBhItemTimestamp.text = dateFmt.format(Date(item.savedAt))
                holder.itemBinding.tvBhItemTitle.text = item.questionTitle
                holder.itemBinding.tvBhItemSubtitle.text = "Topic: ${item.topic}"
                holder.itemBinding.tvBhItemStatus.text = "★ Saved in Bookmarks"
                holder.itemBinding.tvBhItemStatus.setTextColor(
                    ContextCompat.getColor(ctx, R.color.cq_cyan_bright)
                )
                styleDifficultyBadge(holder, ctx, item.difficulty)

                holder.itemBinding.btnBhSecondaryAction.visibility = View.VISIBLE
                holder.itemBinding.btnBhSecondaryAction.setOnClickListener {
                    onRemoveBookmark(item.questionId)
                }
                holder.itemBinding.btnBhOpenProblem.setOnClickListener {
                    onOpenProblem(item.questionId)
                }
            } else {
                val att = history[position]
                holder.itemBinding.tvBhItemQid.text = att.questionId
                holder.itemBinding.tvBhItemBadge.text = att.language
                holder.itemBinding.tvBhItemBadge.setBackgroundResource(R.drawable.bg_pill_cyan)
                holder.itemBinding.tvBhItemBadge.setTextColor(
                    ContextCompat.getColor(ctx, R.color.cq_on_secondary_container)
                )
                holder.itemBinding.tvBhItemTimestamp.text = dateFmt.format(Date(att.timestamp))
                holder.itemBinding.tvBhItemTitle.text =
                    att.questionTitle.ifBlank { "Problem ${att.questionId}" }
                holder.itemBinding.tvBhItemSubtitle.text =
                    if (att.topic.isNotBlank()) "Topic: ${att.topic} • ${att.difficulty}"
                    else "Language: ${att.language}"

                if (att.passed) {
                    holder.itemBinding.tvBhItemStatus.text =
                        "✓ Solved (${att.statusMessage.ifBlank { "Verified" }})"
                    holder.itemBinding.tvBhItemStatus.setTextColor(
                        ContextCompat.getColor(ctx, R.color.cq_easy)
                    )
                } else {
                    holder.itemBinding.tvBhItemStatus.text =
                        "• Attempted (${att.statusMessage.ifBlank { "Not Solved" }})"
                    holder.itemBinding.tvBhItemStatus.setTextColor(
                        ContextCompat.getColor(ctx, R.color.cq_warning)
                    )
                }

                holder.itemBinding.btnBhSecondaryAction.visibility = View.GONE
                holder.itemBinding.btnBhOpenProblem.setOnClickListener {
                    onOpenProblem(att.questionId)
                }
            }
        }

        private fun styleDifficultyBadge(holder: RowViewHolder, ctx: Context, difficulty: String) {
            when (difficulty) {
                "Easy" -> {
                    holder.itemBinding.tvBhItemBadge.setBackgroundResource(R.drawable.bg_badge_easy)
                    holder.itemBinding.tvBhItemBadge.setTextColor(
                        ContextCompat.getColor(ctx, R.color.cq_easy)
                    )
                }
                "Medium" -> {
                    holder.itemBinding.tvBhItemBadge.setBackgroundResource(R.drawable.bg_badge_medium)
                    holder.itemBinding.tvBhItemBadge.setTextColor(
                        ContextCompat.getColor(ctx, R.color.cq_medium)
                    )
                }
                else -> {
                    holder.itemBinding.tvBhItemBadge.setBackgroundResource(R.drawable.bg_badge_hard)
                    holder.itemBinding.tvBhItemBadge.setTextColor(
                        ContextCompat.getColor(ctx, R.color.cq_hard)
                    )
                }
            }
        }

        override fun getItemCount(): Int =
            if (isHistoryMode) history.size else bookmarks.size
    }

    companion object {
        private const val EXTRA_OPEN_HISTORY_TAB = "extra_open_history_tab"

        fun createIntent(context: Context, openHistoryTab: Boolean = false): Intent {
            return Intent(context, BookmarksHistoryActivity::class.java).apply {
                putExtra(EXTRA_OPEN_HISTORY_TAB, openHistoryTab)
            }
        }
    }
}
