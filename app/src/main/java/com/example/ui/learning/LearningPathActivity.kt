package com.example.ui.learning

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.CodeQuestApp
import com.example.MainActivity
import com.example.databinding.ActivityLearningPathBinding
import com.example.databinding.ItemTopicPathBinding
import com.example.model.TopicProgressEntity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class LearningPathActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLearningPathBinding
    private lateinit var topicAdapter: TopicPathAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as CodeQuestApp
        app.sessionPreferences.applySavedTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityLearningPathBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val userId = app.sessionPreferences.loggedInUserId

        binding.btnLearningBack.setOnClickListener {
            finish()
        }

        binding.btnLearningCheatsheet.setOnClickListener {
            showDsaCheatSheetDialog()
        }

        topicAdapter = TopicPathAdapter(
            onStudyTopicClicked = { selectedTopic ->
                showTopicStudyModuleDialog(app, userId, selectedTopic)
            },
            onPracticeTopicClicked = { selectedTopic ->
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_OPEN_TOPIC_FILTER, selectedTopic.topicName)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                finish()
            }
        )

        binding.rvLearningTopics.layoutManager = LinearLayoutManager(this)
        binding.rvLearningTopics.adapter = topicAdapter

        lifecycleScope.launch {
            app.learningRepository.ensureInitializedForUser(userId)
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.learningRepository.observeTopicProgress(userId).collect { topics ->
                    topicAdapter.submitList(topics)
                    val totalSolved = topics.sumOf { it.solvedCount.coerceAtMost(it.maxCount) }
                    val totalMax = topics.sumOf { it.maxCount }.coerceAtLeast(80)
                    val overallPct = (totalSolved * 100) / totalMax
                    binding.tvLearningOverallSummary.text =
                        "${topics.size} Topics • $totalSolved/$totalMax Verified Solves ($overallPct%)"
                }
            }
        }
    }

    private fun showTopicStudyModuleDialog(
        app: CodeQuestApp,
        userId: Long,
        topic: TopicProgressEntity
    ) {
        val studyNotes = getStudyGuideForTopic(topic.topicName)
        MaterialAlertDialogBuilder(this)
            .setTitle("Path 0${topic.topicIndex}: ${topic.topicName}")
            .setMessage(studyNotes)
            .setPositiveButton("Complete Study Session") { _, _ ->
                if (userId > 0L) {
                    lifecycleScope.launch {
                        val newlyCompleted = app.learningRepository.completeStudySessionAction(userId)
                        val msg = if (newlyCompleted) {
                            "Study Session Completed! 'Learn for 15 Minutes' Daily Goal completed (+15 XP)."
                        } else {
                            "Topic notes reviewed! Note: Topic progress (${topic.solvedCount}/${topic.maxCount}) only increases when you solve unique problems."
                        }
                        Toast.makeText(this@LearningPathActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNeutralButton("Practice Topic") { _, _ ->
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_OPEN_TOPIC_FILTER, topic.topicName)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun getStudyGuideForTopic(topicName: String): String {
        return when (topicName) {
            "Programming Basics" ->
                "Core Concepts:\n" +
                    "• Prefer 64-bit integers (Long / int64_t) when multiplying two 10^9 inputs to avoid 32-bit signed overflow.\n" +
                    "• Integer division truncates toward zero; use (a + b - 1) / b for ceiling division on positive integers.\n" +
                    "• Fast I/O matters when input size exceeds 10^5 lines."
            "Conditions" ->
                "Core Concepts:\n" +
                    "• Guard clauses simplify deeply nested if-else trees.\n" +
                    "• Leap Year Rule: divisible by 400 OR (divisible by 4 AND NOT divisible by 100).\n" +
                    "• Triangle Inequality: sort sides a <= b <= c first, then verify a > 0 and a + b > c using 64-bit addition."
            "Loops" ->
                "Core Concepts:\n" +
                    "• Closed-form formulas (like k*(k+1)/2) can replace O(N) loops with O(1) math.\n" +
                    "• Digit extraction pattern: while (n > 0) { d = n % 10; n /= 10 } runs in O(log10 N) iterations.\n" +
                    "• Always verify loop termination conditions on boundary values (N = 0, N = 1)."
            "Strings" ->
                "Core Concepts:\n" +
                    "• Two-pointer palindrome scan compares s[left] and s[right] in O(N) time and O(1) extra space.\n" +
                    "• Frequency arrays of size 26 or 256 outperform hash maps on fixed ASCII alphabets.\n" +
                    "• Sliding window tracks unique substrings in O(N) by advancing the left pointer past the last seen index."
            "Arrays" ->
                "Core Concepts:\n" +
                    "• Single-pass tracking finds min, max, and running sum in O(N) time.\n" +
                    "• Kadane's Algorithm maintains currentBest = max(nums[i], currentBest + nums[i]) for maximum subarray sum.\n" +
                    "• Prefix & Suffix products solve 'Product Except Self' in O(N) without division."
            "Number Theory" ->
                "Core Concepts:\n" +
                    "• Trial division for primality only needs to check divisors up to sqrt(N) using 6k ± 1 steps.\n" +
                    "• Euclidean GCD: gcd(a, b) = gcd(b, a % b) in O(log(min(a,b))).\n" +
                    "• Overflow-safe LCM: compute (a / gcd(a, b)) * b, dividing before multiplying."
            "Patterns" ->
                "Core Concepts:\n" +
                    "• Coordinate distance from center max(|r - mid|, |c - mid|) cleanly generates concentric rings and diamonds.\n" +
                    "• Pascal's Triangle row values follow C(r, k) = C(r, k-1) * (r - k + 1) / k without factorials."
            else ->
                "Core Concepts:\n" +
                    "• Hash map complement lookup solves Two Sum in O(N) time.\n" +
                    "• Stack (LIFO) validates nested bracket sequences in a single pass.\n" +
                    "• Binary Search halves the search space each step for O(log N) lookup on sorted data."
        }
    }

    private fun showDsaCheatSheetDialog() {
        val cheatSheetContent = """
            ⚡ BIG-O COMPLEXITY REFERENCE
            • O(1): Constant (Direct Array index, Hash Map lookup)
            • O(log N): Logarithmic (Binary Search, Balanced BST)
            • O(N): Linear (Single-pass Array scan, Two Pointers)
            • O(N log N): Linearithmic (Merge Sort, Heap Sort)
            • O(N²): Quadratic (Nested loops, Bubble Sort)
            • O(2ᴺ): Exponential (Recursive Subsets, TSP)

            🧠 TOP CODING PATTERNS
            1. Two Pointers: Left & right pointers on sorted arrays.
            2. Sliding Window: Dynamic subsegment for substring problems.
            3. Fast & Slow Pointers: Cycle detection in linked lists.
            4. Prefix Sums: O(1) range sum queries after O(N) preprocessing.
            5. Binary Search on Answer: Monotonic feasibility functions.
            6. Monotonic Stack: Next Greater / Smaller element in O(N).

            💡 CRITICAL BOUNDARY RULES
            • Integers: 32-bit max is ~2.14 × 10⁹. If answers exceed, use 64-bit Long!
            • Modulo Math: (A + B) % M = ((A % M) + (B % M)) % M
            • Array Indices: Always check 0 <= index < size before accessing!
        """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle("DSA & Big-O Cheat Sheet")
            .setMessage(cheatSheetContent)
            .setPositiveButton("Got It", null)
            .show()
    }

    private class TopicPathAdapter(
        private val onStudyTopicClicked: (TopicProgressEntity) -> Unit,
        private val onPracticeTopicClicked: (TopicProgressEntity) -> Unit
    ) : RecyclerView.Adapter<TopicPathAdapter.TopicViewHolder>() {

        private var items: List<TopicProgressEntity> = emptyList()

        fun submitList(newItems: List<TopicProgressEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class TopicViewHolder(val itemBinding: ItemTopicPathBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TopicViewHolder {
            val b = ItemTopicPathBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return TopicViewHolder(b)
        }

        override fun onBindViewHolder(holder: TopicViewHolder, position: Int) {
            val item = items[position]
            val clampedSolved = item.solvedCount.coerceIn(0, item.maxCount)
            val pct = item.percentage

            holder.itemBinding.tvTopicIndexBadge.text =
                String.format("PATH %02d", item.topicIndex)
            holder.itemBinding.tvTopicName.text = item.topicName
            holder.itemBinding.tvTopicDescription.text = item.description
            holder.itemBinding.tvTopicFraction.text = "$clampedSolved/${item.maxCount}"
            holder.itemBinding.tvTopicPercentage.text = "$pct%"
            holder.itemBinding.progressTopicBar.max = item.maxCount
            holder.itemBinding.progressTopicBar.progress = clampedSolved

            holder.itemBinding.btnTopicStudyGuide.setOnClickListener {
                onStudyTopicClicked(item)
            }
            holder.itemBinding.btnTopicPractice.setOnClickListener {
                onPracticeTopicClicked(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
