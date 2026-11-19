package com.example.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.CodeQuestApp
import com.example.MainActivity
import com.example.R
import com.example.data.repository.LearningRepository
import com.example.databinding.ActivityDailyGoalsBinding
import com.example.databinding.ItemDailyGoalBinding
import com.example.model.DailyGoalEntity
import com.example.ui.learning.LearningPathActivity
import com.example.ui.practice.ProblemDetailActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DailyGoalsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDailyGoalsBinding
    private lateinit var goalsAdapter: DailyGoalsAdapter
    private var previousCompletedCount: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as CodeQuestApp
        app.sessionPreferences.applySavedTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityDailyGoalsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val userId = app.sessionPreferences.loggedInUserId
        val readableDate = SimpleDateFormat("EEEE, MMM d, yyyy", Locale.US).format(Date())
        binding.tvGoalsDate.text = "Today • $readableDate"

        binding.btnGoalsBack.setOnClickListener {
            finish()
        }

        goalsAdapter = DailyGoalsAdapter { goal ->
            handleGoalActionClicked(app, userId, goal)
        }
        binding.rvDailyGoals.layoutManager = LinearLayoutManager(this)
        binding.rvDailyGoals.adapter = goalsAdapter

        binding.btnLaunchConceptQuiz.setOnClickListener {
            showInteractiveConceptQuiz(app, userId)
        }

        // Important: Opening DailyGoalsActivity NEVER fakes goal completion.
        lifecycleScope.launch {
            app.learningRepository.ensureInitializedForUser(userId)
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.learningRepository.observeDailyGoals(userId).collect { goals ->
                    goalsAdapter.submitList(goals)
                    val completed = goals.count { it.isCompleted }
                    val total = goals.size.coerceAtLeast(1)
                    binding.tvGoalsOverallFraction.text = "$completed/$total Completed"
                    binding.progressGoalsOverall.max = total
                    binding.progressGoalsOverall.progress = completed

                    val quizGoal = goals.firstOrNull { it.goalKey == LearningRepository.GOAL_CONCEPT_QUIZ }
                    if (quizGoal?.isCompleted == true) {
                        binding.btnLaunchConceptQuiz.text = "Concept Quiz Completed (✓ +10 XP)"
                        binding.btnLaunchConceptQuiz.isEnabled = false
                    } else {
                        binding.btnLaunchConceptQuiz.text = "Take Today's Concept Quiz (+10 XP)"
                        binding.btnLaunchConceptQuiz.isEnabled = true
                    }

                    if (completed > 0) {
                        binding.cardGoalCompletionAnim.visibility = View.VISIBLE
                        val totalEarnedXp = goals.filter { it.isCompleted }.sumOf { it.xpReward }
                        binding.tvGoalCompletionBanner.text =
                            if (completed >= 3) {
                                "Streak Qualified! $completed/$total Daily Goals completed (+$totalEarnedXp XP earned today)."
                            } else {
                                "$completed/$total Daily Goals completed (+$totalEarnedXp XP earned). Complete ${3 - completed} more to qualify for today's streak!"
                            }

                        if (previousCompletedCount in 0 until completed) {
                            binding.cardGoalCompletionAnim.scaleX = 0.92f
                            binding.cardGoalCompletionAnim.scaleY = 0.92f
                            binding.cardGoalCompletionAnim.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(300L)
                                .start()
                        }
                    } else {
                        binding.cardGoalCompletionAnim.visibility = View.GONE
                    }
                    previousCompletedCount = completed
                }
            }
        }
    }

    private fun handleGoalActionClicked(app: CodeQuestApp, userId: Long, goal: DailyGoalEntity) {
        if (goal.isCompleted) {
            Toast.makeText(this, "Goal '${goal.title}' is already completed today!", Toast.LENGTH_SHORT).show()
            return
        }

        when (goal.goalKey) {
            LearningRepository.GOAL_SOLVE_EASY -> {
                lifecycleScope.launch {
                    val easyProblem = app.learningRepository.getRandomQuestionByDifficulty("Easy")
                    if (easyProblem != null) {
                        startActivity(ProblemDetailActivity.createIntent(this@DailyGoalsActivity, easyProblem.id))
                    }
                }
            }
            LearningRepository.GOAL_SOLVE_MEDIUM -> {
                lifecycleScope.launch {
                    val medProblem = app.learningRepository.getRandomQuestionByDifficulty("Medium")
                    if (medProblem != null) {
                        startActivity(ProblemDetailActivity.createIntent(this@DailyGoalsActivity, medProblem.id))
                    }
                }
            }
            LearningRepository.GOAL_SOLVE_HARD -> {
                lifecycleScope.launch {
                    val hardProblem = app.learningRepository.getRandomQuestionByDifficulty("Hard")
                    if (hardProblem != null) {
                        startActivity(ProblemDetailActivity.createIntent(this@DailyGoalsActivity, hardProblem.id))
                    }
                }
            }
            LearningRepository.GOAL_DAILY_CHALLENGE -> {
                lifecycleScope.launch {
                    val challenge = app.learningRepository.observeDailyChallenge(userId).firstOrNull()
                    if (challenge != null) {
                        startActivity(
                            ProblemDetailActivity.createIntent(
                                this@DailyGoalsActivity,
                                challenge.question.id
                            )
                        )
                    }
                }
            }
            LearningRepository.GOAL_CONCEPT_QUIZ -> {
                showInteractiveConceptQuiz(app, userId)
            }
            LearningRepository.GOAL_LEARN_15_MIN -> {
                startActivity(Intent(this, LearningPathActivity::class.java))
            }
            LearningRepository.GOAL_REVIEW_MISTAKE -> {
                lifecycleScope.launch {
                    val mistakes = app.learningRepository.observeMistakes(userId).firstOrNull().orEmpty()
                    if (mistakes.isNotEmpty()) {
                        startActivity(
                            ProblemDetailActivity.createIntent(
                                this@DailyGoalsActivity,
                                mistakes.first().questionId
                            )
                        )
                    } else {
                        Toast.makeText(
                            this@DailyGoalsActivity,
                            "Save a problem to your Mistakes Notebook first, then review it in Problem Details!",
                            Toast.LENGTH_LONG
                        ).show()
                        val firstQ = app.learningRepository.getRandomQuestionByDifficulty("Medium")
                        if (firstQ != null) {
                            startActivity(ProblemDetailActivity.createIntent(this@DailyGoalsActivity, firstQ.id))
                        }
                    }
                }
            }
            LearningRepository.GOAL_ASK_AI_MENTOR -> {
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_OPEN_TAB_ID, R.id.nav_ai)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(intent)
                finish()
            }
        }
    }

    /**
     * Interactive 3-question coding concept quiz.
     * Only completes the "Complete Concept Quiz (+10 XP)" goal when all 3 questions are answered correctly.
     */
    private fun showInteractiveConceptQuiz(app: CodeQuestApp, userId: Long) {
        val q1Options = arrayOf(
            "O(1)",
            "O(log N)",
            "O(N)",
            "O(N log N)"
        )
        var selected1 = 0
        MaterialAlertDialogBuilder(this)
            .setTitle("Concept Quiz (1/3): Binary Search")
            .setSingleChoiceItems(q1Options, 0) { _, which -> selected1 = which }
            .setPositiveButton("Next") { _, _ ->
                if (selected1 != 1) {
                    Toast.makeText(
                        this,
                        "Incorrect! Binary Search on a sorted array runs in O(log N) time. Try again!",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setPositiveButton
                }
                showConceptQuizStep2(app, userId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showConceptQuizStep2(app: CodeQuestApp, userId: Long) {
        val q2Options = arrayOf(
            "Queue (FIFO)",
            "Stack (LIFO)",
            "Min-Heap",
            "Disjoint Set"
        )
        var selected2 = 0
        MaterialAlertDialogBuilder(this)
            .setTitle("Concept Quiz (2/3): Bracket Matching")
            .setSingleChoiceItems(q2Options, 0) { _, which -> selected2 = which }
            .setPositiveButton("Next") { _, _ ->
                if (selected2 != 1) {
                    Toast.makeText(
                        this,
                        "Incorrect! Balanced parentheses validation uses a Stack (LIFO). Try again!",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setPositiveButton
                }
                showConceptQuizStep3(app, userId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showConceptQuizStep3(app: CodeQuestApp, userId: Long) {
        val q3Options = arrayOf(
            "x and (x - 1) == 0",
            "x or (x + 1) == 0",
            "x xor x == 1",
            "x shl 1 == 0"
        )
        var selected3 = 0
        MaterialAlertDialogBuilder(this)
            .setTitle("Concept Quiz (3/3): Bitwise Power of Two")
            .setSingleChoiceItems(q3Options, 0) { _, which -> selected3 = which }
            .setPositiveButton("Submit Quiz") { _, _ ->
                if (selected3 != 0) {
                    Toast.makeText(
                        this,
                        "Incorrect! For x > 0, (x and (x - 1)) == 0 checks if x has a single set bit. Try again!",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val newlyCompleted = app.learningRepository.completeConceptQuizAction(userId)
                    if (newlyCompleted) {
                        Toast.makeText(
                            this@DailyGoalsActivity,
                            "3/3 Correct! 'Complete Concept Quiz' goal completed (+10 XP)!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class DailyGoalsAdapter(
        private val onActionClicked: (DailyGoalEntity) -> Unit
    ) : RecyclerView.Adapter<DailyGoalsAdapter.GoalViewHolder>() {

        private var items: List<DailyGoalEntity> = emptyList()

        fun submitList(newItems: List<DailyGoalEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class GoalViewHolder(val itemBinding: ItemDailyGoalBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GoalViewHolder {
            val b = ItemDailyGoalBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return GoalViewHolder(b)
        }

        override fun onBindViewHolder(holder: GoalViewHolder, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            holder.itemBinding.tvGoalTitle.text = item.title
            holder.itemBinding.tvGoalXpReward.text = "+${item.xpReward} XP"

            if (item.isCompleted) {
                holder.itemBinding.tvGoalStatusSubtitle.text = "✓ Completed Today"
                holder.itemBinding.tvGoalStatusSubtitle.setTextColor(
                    ContextCompat.getColor(ctx, R.color.cq_easy)
                )
                holder.itemBinding.ivGoalStatusIcon.alpha = 1.0f
                holder.itemBinding.btnGoalGo.text = "Done"
                holder.itemBinding.btnGoalGo.isEnabled = false
            } else {
                holder.itemBinding.tvGoalStatusSubtitle.text = "Requires real completion action"
                holder.itemBinding.tvGoalStatusSubtitle.setTextColor(
                    ContextCompat.getColor(ctx, R.color.cq_text_secondary)
                )
                holder.itemBinding.ivGoalStatusIcon.alpha = 0.28f
                holder.itemBinding.btnGoalGo.text = "Start"
                holder.itemBinding.btnGoalGo.isEnabled = true
            }

            holder.itemBinding.btnGoalGo.setOnClickListener {
                onActionClicked(item)
            }
            holder.itemBinding.cardDailyGoalItem.setOnClickListener {
                onActionClicked(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
