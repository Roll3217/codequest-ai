package com.example.ui.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.CodeQuestApp
import com.example.R
import com.example.data.remote.GeminiApiClient
import com.example.data.remote.LearnerProfileContext
import com.example.databinding.FragmentAiMentorBinding
import com.example.model.UserEntity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AiMentorFragment : Fragment() {

    private var _binding: FragmentAiMentorBinding? = null
    private val binding get() = _binding!!
    private var currentUser: UserEntity? = null
    private var solvedCount: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAiMentorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as CodeQuestApp
        val userId = app.sessionPreferences.loggedInUserId

        updateConfigBadge()
        setupLearnerMemoryObserver(app, userId)
        setupEditInterestsButton(app, userId)

        binding.chipAiTwoSum.setOnClickListener {
            submitPrompt("Explain the optimal Two Sum approach using a Hash Map and analyze its time and space complexity.")
        }
        binding.chipAiKadane.setOnClickListener {
            submitPrompt("Explain Kadane's Algorithm for Maximum Subarray Sum with step-by-step logic, code, and complexity.")
        }
        binding.chipAiSlidingWindow.setOnClickListener {
            submitPrompt("Compare Sliding Window vs Two Pointers. When should I use each pattern on arrays and strings?")
        }
        binding.chipAiBigO.setOnClickListener {
            submitPrompt("Provide a cheat-sheet explanation of Big-O complexity classes: O(1), O(log N), O(N), O(N log N), O(N²).")
        }

        binding.btnAiSend.setOnClickListener {
            val query = binding.etAiPrompt.text?.toString().orEmpty().trim()
            if (query.isNotBlank()) {
                submitPrompt(query)
            }
        }

        binding.etAiPrompt.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.scrollAiConversation.postDelayed({
                    binding.scrollAiConversation.fullScroll(View.FOCUS_DOWN)
                }, 100L)
            }
        }

        binding.etAiPrompt.setOnClickListener {
            binding.scrollAiConversation.postDelayed({
                binding.scrollAiConversation.fullScroll(View.FOCUS_DOWN)
            }, 100L)
        }

        binding.btnAiCopyResponse.setOnClickListener {
            val responseText = binding.tvAiResponseBody.text?.toString().orEmpty()
            if (responseText.isNotBlank()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("CodeQuest AI Guidance", responseText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "Response copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupLearnerMemoryObserver(app: CodeQuestApp, userId: Long) {
        if (userId <= 0L) {
            binding.tvAiLearnerName.text = "Mentoring CodeQuest Learner"
            binding.tvAiLearnerInterests.text = "🎯 Focus: Arrays, Dynamic Programming, Graphs"
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            launch {
                app.authRepository.observeUser(userId).collectLatest { user ->
                    currentUser = user
                    if (user != null) {
                        val displayName = user.fullName.ifBlank { "Learner" }
                        val streakText = if (user.currentStreak > 0) "${user.currentStreak}-day streak" else "Streak ready"
                        binding.tvAiLearnerName.text = "Mentoring $displayName • Lvl ${user.level} ($streakText)"
                        binding.tvAiLearnerInterests.text = "🎯 Focus: ${user.interests.ifBlank { "Arrays, Dynamic Programming, Graphs" }}"
                    }
                }
            }
            launch {
                app.learningRepository.observeProgressAnalytics(userId).collectLatest { snapshot ->
                    solvedCount = snapshot.totalSolved
                }
            }
        }
    }

    private fun setupEditInterestsButton(app: CodeQuestApp, userId: Long) {
        val availableTopics = arrayOf(
            "Arrays & Two Pointers",
            "Strings & Palindromes",
            "Dynamic Programming",
            "Trees & Binary Search Trees",
            "Graphs & BFS/DFS",
            "Greedy Algorithms",
            "Number Theory",
            "System Design",
            "Kotlin",
            "Python",
            "Java",
            "C++"
        )

        binding.btnAiEditInterests.setOnClickListener {
            val currentInterests = currentUser?.interests.orEmpty()
            val selectedIndices = BooleanArray(availableTopics.size) { i ->
                currentInterests.contains(availableTopics[i], ignoreCase = true)
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Customize AI Mentor Memory")
                .setMultiChoiceItems(availableTopics, selectedIndices) { _, which, isChecked ->
                    selectedIndices[which] = isChecked
                }
                .setPositiveButton("Save Memory") { _, _ ->
                    val chosen = availableTopics.filterIndexed { idx, _ -> selectedIndices[idx] }
                    val newInterestsStr = if (chosen.isEmpty()) {
                        "Arrays, Dynamic Programming, Graphs"
                    } else {
                        chosen.joinToString(", ")
                    }

                    if (userId > 0L) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            app.authRepository.updateInterests(userId, newInterestsStr)
                            val name = currentUser?.fullName ?: "Learner"
                            Toast.makeText(
                                requireContext(),
                                "AI Mentor memory updated for $name!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        binding.tvAiLearnerInterests.text = "🎯 Focus: $newInterestsStr"
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updateConfigBadge() {
        val configured = GeminiApiClient.isConfigured()
        if (configured) {
            binding.tvAiStatusBadge.text = "AI MENTOR • LIVE"
            binding.tvAiStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.cq_easy))
            binding.tvAiStatusBadge.setBackgroundResource(R.drawable.bg_pill_cyan)
        } else {
            binding.tvAiStatusBadge.text = "SETUP NEEDED"
            binding.tvAiStatusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.cq_warning))
            binding.tvAiStatusBadge.setBackgroundResource(R.drawable.bg_pill_purple)
        }
    }

    private fun submitPrompt(prompt: String) {
        val app = requireActivity().application as CodeQuestApp
        val userId = app.sessionPreferences.loggedInUserId

        // Hide keyboard
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etAiPrompt.windowToken, 0)

        binding.etAiPrompt.setText("")
        binding.cardAiUserQuery.visibility = View.VISIBLE
        binding.tvAiUserQueryText.text = prompt
        binding.layoutAiLoading.visibility = View.VISIBLE
        binding.tvAiResponseTitle.text = "AI Mentor Guidance (Streaming Live)"
        binding.tvAiResponseBody.text = ""
        binding.btnAiSend.isEnabled = false

        binding.scrollAiConversation.post {
            binding.scrollAiConversation.fullScroll(View.FOCUS_DOWN)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val liveBuilder = StringBuilder()
            var firstChunkReceived = false

            val learner = currentUser?.let { u ->
                LearnerProfileContext(
                    name = u.fullName,
                    email = u.email,
                    interests = u.interests.ifBlank { "Arrays, Dynamic Programming, Graphs" },
                    level = u.level,
                    xp = u.xp,
                    streak = u.currentStreak,
                    solvedCount = solvedCount,
                    preferredLanguage = u.preferredLanguage.ifBlank { "Kotlin" }
                )
            }

            val result = app.aiRepository.askMentor(
                prompt = prompt,
                learner = learner,
                onStreamChunk = { chunk ->
                    val b = _binding ?: return@askMentor
                    if (!firstChunkReceived) {
                        firstChunkReceived = true
                        b.layoutAiLoading.visibility = View.GONE
                    }
                    liveBuilder.append(chunk)
                    b.tvAiResponseBody.text = liveBuilder.toString()
                    b.scrollAiConversation.post {
                        _binding?.scrollAiConversation?.fullScroll(View.FOCUS_DOWN)
                    }
                }
            )

            val b = _binding ?: return@launch
            b.layoutAiLoading.visibility = View.GONE
            b.btnAiSend.isEnabled = true

            result.onSuccess { fullExplanation ->
                b.tvAiResponseTitle.text = "AI Mentor Guidance"
                b.tvAiResponseBody.text = fullExplanation

                // Record progress action for "Ask AI Mentor" Daily Goal
                if (userId > 0L) {
                    app.learningRepository.completeAskAiMentorAction(userId)
                }

                b.scrollAiConversation.post {
                    _binding?.scrollAiConversation?.fullScroll(View.FOCUS_DOWN)
                }
            }.onFailure { error ->
                val errorMsg = error.message ?: "Failed to generate AI response."
                when {
                    errorMsg.contains("not configured", ignoreCase = true) -> {
                        b.tvAiResponseTitle.text = "AI Mentor Setup Needed"
                        b.tvAiResponseBody.text =
                            "🤖 GEMINI_API_KEY Is Not Configured\n\n" +
                                "To enable live AI Mentor answers:\n" +
                                "1. Add your Gemini API key ('AIza...' from aistudio.google.com/apikey or 'AQ...' Vertex AI Express key) to Secrets as GEMINI_API_KEY.\n" +
                                "2. Rebuild or refresh the app.\n\n" +
                                "Study Tip for '$prompt':\n" +
                                "You can still use the built-in 8-Topic Learning Path and Practice Hints offline!"
                    }
                    errorMsg.contains("Invalid GEMINI_API_KEY", ignoreCase = true) ||
                        errorMsg.contains("API key not valid", ignoreCase = true) ||
                        errorMsg.contains("API_KEY_INVALID", ignoreCase = true) -> {
                        b.tvAiResponseTitle.text = "API Key Not Accepted"
                        b.tvAiResponseBody.text =
                            "🔑 Gemini API Key Rejected\n\n" +
                                "$errorMsg\n\n" +
                                "Please check that your key in Secrets (GEMINI_API_KEY) is active and not expired, then rebuild."
                    }
                    errorMsg.contains("quota", ignoreCase = true) ||
                        errorMsg.contains("rate limit", ignoreCase = true) ||
                        errorMsg.contains("429") -> {
                        b.tvAiResponseTitle.text = "AI Rate Limit Reached"
                        b.tvAiResponseBody.text =
                            "⏳ Too many AI requests right now ($errorMsg).\n\nPlease wait a moment and tap Send again."
                    }
                    else -> {
                        b.tvAiResponseTitle.text = "Mentor Notice"
                        b.tvAiResponseBody.text =
                            "Unable to reach AI Mentor right now: $errorMsg\n\nPlease check your internet connection and try again."
                    }
                }

                b.scrollAiConversation.post {
                    _binding?.scrollAiConversation?.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
