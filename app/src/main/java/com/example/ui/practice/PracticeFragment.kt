package com.example.ui.practice

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.CodeQuestApp
import com.example.R
import com.example.databinding.FragmentPracticeBinding
import com.example.databinding.ItemQuestionCardBinding
import com.example.model.QuestionWithStatus
import com.example.ui.learning.LearningPathActivity
import com.example.ui.learning.LearningTopicCatalog
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch
import java.util.Locale

class PracticeFragment : Fragment() {

    private var _binding: FragmentPracticeBinding? = null
    private val binding get() = _binding!!

    private var allQuestions: List<QuestionWithStatus> = emptyList()
    private var selectedDifficulty: String = "All"
    private var selectedTopic: String = "All Topics"
    private var searchQuery: String = ""

    private lateinit var questionAdapter: QuestionListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPracticeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as CodeQuestApp
        val userId = app.sessionPreferences.loggedInUserId

        val initialTopicArg = arguments?.getString(ARG_INITIAL_TOPIC)
        if (!initialTopicArg.isNullOrBlank()) {
            selectedTopic = initialTopicArg
        }

        setupTopicChips()
        setupDifficultyChips()

        binding.etPracticeSearch.doAfterTextChanged { editable ->
            searchQuery = editable?.toString().orEmpty().trim()
            applyFilters()
        }

        binding.btnPracticeLearningPath.setOnClickListener {
            startActivity(Intent(requireContext(), LearningPathActivity::class.java))
        }

        questionAdapter = QuestionListAdapter(
            onQuestionClicked = { item ->
                startActivity(ProblemDetailActivity.createIntent(requireContext(), item.question.id))
            },
            onBookmarkToggled = { item ->
                if (userId > 0L) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        app.learningRepository.toggleBookmark(userId, item.question)
                    }
                }
            }
        )

        binding.rvPracticeQuestions.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPracticeQuestions.adapter = questionAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            app.learningRepository.ensureInitializedForUser(userId)
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.learningRepository.observeQuestionsWithStatus(userId).collect { list ->
                    allQuestions = list
                    applyFilters()
                }
            }
        }
    }

    private fun setupDifficultyChips() {
        binding.chipGroupDifficulty.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chip_diff_all
            selectedDifficulty = when (checkedId) {
                R.id.chip_diff_easy -> "Easy"
                R.id.chip_diff_medium -> "Medium"
                R.id.chip_diff_hard -> "Hard"
                else -> "All"
            }
            applyFilters()
        }
    }

    private fun setupTopicChips() {
        val group = binding.chipGroupTopic
        group.removeAllViews()
        val topics = listOf("All Topics") + LearningTopicCatalog.CORE_TOPICS
        topics.forEach { topicName ->
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                text = topicName
                isCheckable = true
                isChecked = topicName.equals(selectedTopic, ignoreCase = true)
            }
            group.addView(chip)
        }

        group.setOnCheckedStateChangeListener { chipGroup, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val chip = chipGroup.findViewById<Chip>(id)
            if (chip != null) {
                selectedTopic = chip.text.toString()
                applyFilters()
            }
        }
    }

    private fun applyFilters() {
        val b = _binding ?: return
        val queryLower = searchQuery.lowercase(Locale.ROOT)
        val filtered = allQuestions.filter { item ->
            val q = item.question
            val matchesDiff = selectedDifficulty == "All" ||
                q.difficulty.equals(selectedDifficulty, ignoreCase = true)
            val matchesTopic = selectedTopic == "All Topics" ||
                q.topic.equals(selectedTopic, ignoreCase = true)
            val matchesQuery = queryLower.isEmpty() ||
                q.title.lowercase(Locale.ROOT).contains(queryLower) ||
                q.id.lowercase(Locale.ROOT).contains(queryLower) ||
                q.topic.lowercase(Locale.ROOT).contains(queryLower) ||
                q.subtopic.lowercase(Locale.ROOT).contains(queryLower) ||
                q.tagsCsv.lowercase(Locale.ROOT).contains(queryLower) ||
                q.description.lowercase(Locale.ROOT).contains(queryLower)
            matchesDiff && matchesTopic && matchesQuery
        }

        questionAdapter.submitList(filtered)
        val solvedCount = filtered.count { it.isSolved }
        b.tvPracticeResultCount.text =
            "${filtered.size} Problems Shown • $solvedCount Solved • Topic: $selectedTopic"
        b.layoutPracticeEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        b.rvPracticeQuestions.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class QuestionListAdapter(
        private val onQuestionClicked: (QuestionWithStatus) -> Unit,
        private val onBookmarkToggled: (QuestionWithStatus) -> Unit
    ) : RecyclerView.Adapter<QuestionListAdapter.QuestionViewHolder>() {

        private var items: List<QuestionWithStatus> = emptyList()

        fun submitList(newItems: List<QuestionWithStatus>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class QuestionViewHolder(val itemBinding: ItemQuestionCardBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QuestionViewHolder {
            val b = ItemQuestionCardBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return QuestionViewHolder(b)
        }

        override fun onBindViewHolder(holder: QuestionViewHolder, position: Int) {
            val item = items[position]
            val q = item.question
            val ctx = holder.itemView.context

            holder.itemBinding.tvItemQuestionId.text = q.id
            holder.itemBinding.tvItemTitle.text = q.title
            holder.itemBinding.tvItemTopic.text = q.topic
            holder.itemBinding.tvItemSubtopic.text = q.subtopic
            holder.itemBinding.tvItemDescription.text = q.description
            holder.itemBinding.tvItemDifficulty.text = q.difficulty

            when (q.difficulty) {
                "Easy" -> {
                    holder.itemBinding.tvItemDifficulty.setBackgroundResource(R.drawable.bg_badge_easy)
                    holder.itemBinding.tvItemDifficulty.setTextColor(
                        ContextCompat.getColor(ctx, R.color.cq_easy)
                    )
                }
                "Medium" -> {
                    holder.itemBinding.tvItemDifficulty.setBackgroundResource(R.drawable.bg_badge_medium)
                    holder.itemBinding.tvItemDifficulty.setTextColor(
                        ContextCompat.getColor(ctx, R.color.cq_medium)
                    )
                }
                else -> {
                    holder.itemBinding.tvItemDifficulty.setBackgroundResource(R.drawable.bg_badge_hard)
                    holder.itemBinding.tvItemDifficulty.setTextColor(
                        ContextCompat.getColor(ctx, R.color.cq_hard)
                    )
                }
            }

            if (item.isSolved) {
                holder.itemBinding.tvItemSolvedStatus.text = "✓ Solved"
                holder.itemBinding.tvItemSolvedStatus.setTextColor(
                    ContextCompat.getColor(ctx, R.color.cq_easy)
                )
            } else {
                holder.itemBinding.tvItemSolvedStatus.text = "Unsolved"
                holder.itemBinding.tvItemSolvedStatus.setTextColor(
                    ContextCompat.getColor(ctx, R.color.cq_text_muted)
                )
            }

            holder.itemBinding.btnItemBookmark.setImageResource(
                if (item.isBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline
            )

            holder.itemBinding.btnItemBookmark.setOnClickListener {
                onBookmarkToggled(item)
            }
            holder.itemBinding.cardQuestionItem.setOnClickListener {
                onQuestionClicked(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        private const val ARG_INITIAL_TOPIC = "arg_initial_topic"

        fun newInstance(initialTopic: String? = null): PracticeFragment {
            return PracticeFragment().apply {
                if (!initialTopic.isNullOrBlank()) {
                    arguments = Bundle().apply {
                        putString(ARG_INITIAL_TOPIC, initialTopic)
                    }
                }
            }
        }
    }
}
