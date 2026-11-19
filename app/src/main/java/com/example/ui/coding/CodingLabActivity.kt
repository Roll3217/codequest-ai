package com.example.ui.coding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.CodeQuestApp
import com.example.R
import com.example.data.local.QuestionBankSeed
import com.example.databinding.ActivityCodingLabBinding
import com.example.model.QuestionEntity
import com.example.service.ExecutionStatus
import com.example.utils.AppConfig
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class CodingLabActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCodingLabBinding
    private var currentQuestion: QuestionEntity? = null
    private var selectedLanguage: SupportedLanguage = SupportedLanguage.KOTLIN

    // Preserves user's edited code per language during the session
    private val codeDraftsByLanguage = mutableMapOf<SupportedLanguage, String>()
    private var isUpdatingEditorProgrammatically = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as CodeQuestApp
        app.sessionPreferences.applySavedTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityCodingLabBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val questionId = intent.getStringExtra(EXTRA_QUESTION_ID) ?: "PB-01"
        val userId = app.sessionPreferences.loggedInUserId

        binding.btnLabBack.setOnClickListener {
            finish()
        }

        binding.etCodeEditor.doAfterTextChanged { editable ->
            if (!isUpdatingEditorProgrammatically) {
                val text = editable?.toString().orEmpty()
                codeDraftsByLanguage[selectedLanguage] = text
                binding.tvLabCharCount.text = "${text.lines().size} lines • ${text.length} chars"
            }
        }

        binding.etCodeEditor.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.postDelayed({
                    var top = 0
                    var current: View? = view
                    while (current != null && current != binding.labScrollView) {
                        top += current.top
                        current = current.parent as? View
                    }
                    binding.labScrollView.smoothScrollTo(0, (top - 48).coerceAtLeast(0))
                }, 150L)
            }
        }

        binding.chipGroupLanguages.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val newLang = when (checkedId) {
                R.id.chip_lang_kotlin -> SupportedLanguage.KOTLIN
                R.id.chip_lang_java -> SupportedLanguage.JAVA
                R.id.chip_lang_python -> SupportedLanguage.PYTHON
                R.id.chip_lang_cpp -> SupportedLanguage.CPP
                R.id.chip_lang_c -> SupportedLanguage.C
                R.id.chip_lang_js -> SupportedLanguage.JAVASCRIPT
                else -> SupportedLanguage.KOTLIN
            }
            switchLanguage(newLang)
        }

        binding.btnLabResetCode.setOnClickListener {
            val q = currentQuestion ?: return@setOnClickListener
            val starter = QuestionBankSeed.getStarterCodeForLanguage(
                q.starterCodeJson,
                selectedLanguage.displayName
            )
            codeDraftsByLanguage[selectedLanguage] = starter
            setEditorText(starter)
            binding.tvLabConsoleBadge.text = "RESET"
            binding.tvLabConsoleOutput.text =
                "Starter code restored for ${selectedLanguage.displayName}."
        }

        binding.btnLabCopyCode.setOnClickListener {
            val text = binding.etCodeEditor.text?.toString().orEmpty()
            if (text.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("CodeQuest Solution", text)
                clipboard.setPrimaryClip(clip)
                android.widget.Toast.makeText(this, "Code copied to clipboard!", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnLabFormatCode.setOnClickListener {
            val original = binding.etCodeEditor.text?.toString().orEmpty()
            if (original.isNotBlank()) {
                val formatted = formatSourceCode(original)
                setEditorText(formatted)
                android.widget.Toast.makeText(this, "Code indented cleanly!", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnLabCheckSyntax.setOnClickListener {
            val text = binding.etCodeEditor.text?.toString().orEmpty()
            val result = checkSyntaxAndBrackets(text)
            binding.tvLabConsoleBadge.text = if (result.isValid) "SYNTAX OK" else "SYNTAX NOTICE"
            binding.tvLabConsoleBadge.setTextColor(
                ContextCompat.getColor(this, if (result.isValid) R.color.cq_easy else R.color.cq_warning)
            )
            binding.tvLabConsoleOutput.text = result.summary
            binding.labScrollView.post {
                binding.labScrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }

        setupSymbolChips()

        // RUN button — Never fakes execution; calls CodeExecutionService
        binding.btnLabRun.setOnClickListener {
            val q = currentQuestion ?: return@setOnClickListener
            val sourceCode = binding.etCodeEditor.text?.toString().orEmpty().trim()
            if (sourceCode.isBlank()) {
                binding.tvLabConsoleBadge.text = "EMPTY CODE"
                binding.tvLabConsoleBadge.setTextColor(
                    ContextCompat.getColor(this, R.color.cq_error)
                )
                binding.tvLabConsoleOutput.text = "Please write code before clicking Run."
                binding.layoutLabIoComparison.visibility = View.GONE
                binding.cardLabErrorHelper.visibility = View.VISIBLE
                binding.tvLabErrorLineBadge.text = "📍 Line 1"
                binding.tvLabErrorTitle.text = "Editor Khali Hai / Empty Code"
                binding.tvLabErrorDetail.text = "Aapne code editor me kuch bhi nahi likha hai. Code chalane ke liye pehle apna solution likhein."
                binding.tvLabErrorFix.text = "Function ke andar apna calculation ya code likhein, phir Run par click karein."
                binding.labScrollView.post {
                    binding.labScrollView.fullScroll(android.view.View.FOCUS_DOWN)
                }
                return@setOnClickListener
            }

            lifecycleScope.launch {
                binding.tvLabConsoleBadge.text = "RUNNING…"
                binding.tvLabConsoleBadge.setTextColor(
                    ContextCompat.getColor(this@CodingLabActivity, R.color.cq_cyan_bright)
                )
                val customInput = binding.etLabCustomInput.text?.toString().orEmpty()
                val effectiveInput = if (customInput.isNotBlank()) customInput else q.expectedInput
                val status = app.codeExecutionService.runCode(
                    questionId = q.id,
                    language = selectedLanguage.displayName,
                    sourceCode = sourceCode,
                    sampleInput = effectiveInput
                )
                when (status) {
                    is ExecutionStatus.BackendRequired -> {
                        binding.tvLabConsoleBadge.text = "BACKEND REQUIRED"
                        binding.tvLabConsoleBadge.setTextColor(
                            ContextCompat.getColor(this@CodingLabActivity, R.color.cq_warning)
                        )
                        binding.tvLabConsoleOutput.text =
                            "[Run Halted]\n" +
                                "Language: ${selectedLanguage.displayName}\n" +
                                "Reason: ${status.reason}"
                        binding.layoutLabIoComparison.visibility = View.GONE
                        binding.cardLabErrorHelper.visibility = View.GONE
                        binding.labScrollView.post {
                            binding.labScrollView.fullScroll(android.view.View.FOCUS_DOWN)
                        }
                    }
                    is ExecutionStatus.Executed -> {
                        binding.tvLabConsoleBadge.text =
                            if (status.passed) "SAMPLE PASSED" else "SAMPLE FAILED"
                        binding.tvLabConsoleBadge.setTextColor(
                            ContextCompat.getColor(
                                this@CodingLabActivity,
                                if (status.passed) R.color.cq_success else R.color.cq_error
                            )
                        )
                        binding.tvLabConsoleOutput.text = status.output

                        // Show 3-Box Visual I/O Comparison for non-tech students
                        binding.layoutLabIoComparison.visibility = View.VISIBLE
                        val inGiven = status.inputGiven ?: effectiveInput
                        val expOut = status.expectedOutput ?: q.expectedOutput
                        val actOut = status.actualOutput ?: (if (status.passed) expOut else "Execution Failed / Error")

                        binding.tvLabIoInput.text = inGiven.ifBlank { "(No input required)" }
                        binding.tvLabIoExpected.text = expOut.ifBlank { "(None)" }
                        binding.tvLabIoActual.text = actOut

                        if (status.passed) {
                            binding.layoutLabIoActualBox.setBackgroundResource(R.drawable.bg_box_success)
                            binding.tvLabIoActualLabel.text = "💻 Aapke Code Ka Output (Sahi Hai! ✓):"
                            binding.tvLabIoActualLabel.setTextColor(ContextCompat.getColor(this@CodingLabActivity, R.color.cq_easy))
                            binding.cardLabErrorHelper.visibility = View.GONE
                        } else {
                            binding.layoutLabIoActualBox.setBackgroundResource(R.drawable.bg_box_error)
                            binding.tvLabIoActualLabel.text = "💻 Aapke Code Ka Output (Galti Mili / Mismatch ✗):"
                            binding.tvLabIoActualLabel.setTextColor(ContextCompat.getColor(this@CodingLabActivity, R.color.cq_error))

                            // Show Beginner-Friendly Error Helper
                            binding.cardLabErrorHelper.visibility = View.VISIBLE
                            val line = status.errorLine ?: 1
                            binding.tvLabErrorLineBadge.text = "📍 Line $line"
                            binding.tvLabErrorTitle.text = status.errorTitle ?: "Code Me Issue Mila"
                            binding.tvLabErrorDetail.text = status.errorExplanation ?: "Line $line par syntax ya calculation me galti hai. Console log check karein."
                            binding.tvLabErrorFix.text = status.fixSuggestion ?: "Line $line par brackets ya return statement ko verify karein."
                        }

                        binding.labScrollView.post {
                            binding.labScrollView.fullScroll(android.view.View.FOCUS_DOWN)
                        }
                    }
                }
            }
        }

        // SUBMIT button — Only marks a problem solved when verified against required test cases.
        binding.btnLabSubmit.setOnClickListener {
            val q = currentQuestion ?: return@setOnClickListener
            val sourceCode = binding.etCodeEditor.text?.toString().orEmpty().trim()
            if (sourceCode.isBlank()) {
                binding.tvLabConsoleBadge.text = "EMPTY SUBMISSION"
                binding.tvLabConsoleBadge.setTextColor(
                    ContextCompat.getColor(this, R.color.cq_error)
                )
                binding.tvLabConsoleOutput.text = "Please enter your solution code before submitting."
                binding.layoutLabIoComparison.visibility = View.GONE
                binding.cardLabErrorHelper.visibility = View.VISIBLE
                binding.tvLabErrorLineBadge.text = "📍 Line 1"
                binding.tvLabErrorTitle.text = "Khali Code Submit Nahi Kar Sakte"
                binding.tvLabErrorDetail.text = "Aapne koi solution code nahi likha hai. Submit karne ke liye pehle apna code likhein."
                binding.tvLabErrorFix.text = "Function me apna code likhein aur pehle Run karke check karein."
                binding.labScrollView.post {
                    binding.labScrollView.fullScroll(android.view.View.FOCUS_DOWN)
                }
                return@setOnClickListener
            }

            lifecycleScope.launch {
                binding.tvLabConsoleBadge.text = "EVALUATING…"
                binding.tvLabConsoleBadge.setTextColor(
                    ContextCompat.getColor(this@CodingLabActivity, R.color.cq_cyan_bright)
                )
                val parsedTestCases = try {
                    val arr = org.json.JSONArray(q.testCasesJson)
                    val list = mutableListOf<com.example.data.remote.ExecuteTestCaseDto>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        list.add(
                            com.example.data.remote.ExecuteTestCaseDto(
                                input = obj.optString("in", ""),
                                expectedOutput = obj.optString("out", "")
                            )
                        )
                    }
                    list
                } catch (_: Exception) {
                    listOf(com.example.data.remote.ExecuteTestCaseDto(input = q.expectedInput, expectedOutput = q.expectedOutput))
                }

                val status = app.codeExecutionService.submitSolution(
                    questionId = q.id,
                    language = selectedLanguage.displayName,
                    sourceCode = sourceCode,
                    testCases = parsedTestCases
                )
                when (status) {
                    is ExecutionStatus.BackendRequired -> {
                        if (userId > 0L) {
                            app.learningRepository.recordSubmissionAttempt(
                                userId = userId,
                                question = q,
                                language = selectedLanguage.displayName,
                                sourceCode = sourceCode,
                                passed = false,
                                statusMessage = "Pending Execution Backend"
                            )
                        }
                        binding.tvLabConsoleBadge.text = "BACKEND REQUIRED"
                        binding.tvLabConsoleBadge.setTextColor(
                            ContextCompat.getColor(this@CodingLabActivity, R.color.cq_warning)
                        )
                        binding.tvLabConsoleOutput.text =
                            "[Submission Not Verified]\n" +
                                "• Problem: ${q.id} (${q.title})\n" +
                                "• Language: ${selectedLanguage.displayName}\n" +
                                "• Reason: ${status.reason}"
                        binding.layoutLabIoComparison.visibility = View.GONE
                        binding.cardLabErrorHelper.visibility = View.GONE
                        binding.labScrollView.post {
                            binding.labScrollView.fullScroll(android.view.View.FOCUS_DOWN)
                        }
                    }
                    is ExecutionStatus.Executed -> {
                        if (userId > 0L) {
                            app.learningRepository.recordSubmissionAttempt(
                                userId = userId,
                                question = q,
                                language = selectedLanguage.displayName,
                                sourceCode = sourceCode,
                                passed = status.passed,
                                statusMessage = if (status.passed) "Passed (${status.passedTestCases}/${status.totalTestCases})" else "Failed Test Cases (${status.passedTestCases}/${status.totalTestCases})",
                                recordMistakeOnFailure = !status.passed
                            )
                        }
                        binding.tvLabConsoleBadge.text =
                            if (status.passed) "ACCEPTED" else "WRONG ANSWER"
                        binding.tvLabConsoleBadge.setTextColor(
                            ContextCompat.getColor(
                                this@CodingLabActivity,
                                if (status.passed) R.color.cq_success else R.color.cq_error
                            )
                        )
                        binding.tvLabConsoleOutput.text = status.output

                        // Show 3-Box Visual I/O Comparison
                        binding.layoutLabIoComparison.visibility = View.VISIBLE
                        val inGiven = status.inputGiven ?: (parsedTestCases.firstOrNull()?.input ?: q.expectedInput)
                        val expOut = status.expectedOutput ?: (parsedTestCases.firstOrNull()?.expectedOutput ?: q.expectedOutput)
                        val actOut = status.actualOutput ?: (if (status.passed) expOut else "Test Case Failed")

                        binding.tvLabIoInput.text = inGiven.ifBlank { "(No input required)" }
                        binding.tvLabIoExpected.text = expOut.ifBlank { "(None)" }
                        binding.tvLabIoActual.text = actOut

                        if (status.passed) {
                            binding.layoutLabIoActualBox.setBackgroundResource(R.drawable.bg_box_success)
                            binding.tvLabIoActualLabel.text = "💻 Aapke Code Ka Output (Sahi Hai! ✓):"
                            binding.tvLabIoActualLabel.setTextColor(ContextCompat.getColor(this@CodingLabActivity, R.color.cq_easy))
                            binding.cardLabErrorHelper.visibility = View.GONE
                        } else {
                            binding.layoutLabIoActualBox.setBackgroundResource(R.drawable.bg_box_error)
                            binding.tvLabIoActualLabel.text = "💻 Aapke Code Ka Output (Test Case Fail Hua ✗):"
                            binding.tvLabIoActualLabel.setTextColor(ContextCompat.getColor(this@CodingLabActivity, R.color.cq_error))

                            // Show Beginner-Friendly Error Helper
                            binding.cardLabErrorHelper.visibility = View.VISIBLE
                            val line = status.errorLine ?: 1
                            binding.tvLabErrorLineBadge.text = "📍 Line $line"
                            binding.tvLabErrorTitle.text = status.errorTitle ?: "Test Case Mismatch"
                            binding.tvLabErrorDetail.text = status.errorExplanation ?: "Aapka code kuch test cases par galat output de raha hai (${status.passedTestCases}/${status.totalTestCases} pass hue)."
                            binding.tvLabErrorFix.text = status.fixSuggestion ?: "Corner cases (jaise negative numbers, 0, ya boundary values) check karein."
                        }

                        binding.labScrollView.post {
                            binding.labScrollView.fullScroll(android.view.View.FOCUS_DOWN)
                        }

                        if (!status.passed) {
                            val roasts = listOf(
                                "That code didn't just fail test cases—it hurt the compiler's feelings.",
                                "Infinite loop detected in your logic. Even the test runner needed a coffee break.",
                                "Syntax was clean, but the algorithm took a detour to wrong answers. Check edge cases!",
                                "Off-by-one error strikes again! The oldest trick in the programmer playbook."
                            )
                            val roastMsg = roasts.random()
                            binding.cardLabRoast.visibility = View.VISIBLE
                            binding.tvLabRoastTitle.text = "Compilation Roast (${status.passedTestCases}/${status.totalTestCases} Passed)"
                            binding.tvLabRoastBody.text = roastMsg

                            if (userId > 0L) {
                                app.notificationService.triggerRoastNotification(
                                    context = applicationContext,
                                    userId = userId,
                                    roastType = com.example.service.RoastType.FAILED_CODE_SUBMISSION,
                                    contextInfo = "${q.id}: ${status.passedTestCases}/${status.totalTestCases} passed"
                                )
                            }
                        } else {
                            binding.cardLabRoast.visibility = View.GONE
                            val baseXp = when (q.difficulty) {
                                "Hard" -> 50
                                "Medium" -> 35
                                else -> 20
                            }
                            MaterialAlertDialogBuilder(this@CodingLabActivity)
                                .setTitle("🎉 Solution Accepted!")
                                .setMessage(
                                    "Shabash! Aapka code bilkul sahi hai!\n\n" +
                                        "• Problem: ${q.id} — ${q.title}\n" +
                                        "• Language: ${selectedLanguage.displayName}\n" +
                                        "• Test Cases: ${status.passedTestCases}/${status.totalTestCases} Passed (100%)\n" +
                                        "• Reward: +$baseXp XP (plus Daily Goal & Achievement bonuses if newly unlocked)\n" +
                                        "• Topic Progress (${q.topic}): Updated (+1 solved for first solve)\n\n" +
                                        "Aapka solution save aur sync ho chuka hai."
                                )
                                .setPositiveButton("Next Problem") { _, _ ->
                                    finish()
                                }
                                .setNegativeButton("Continue in Editor", null)
                                .show()
                        }
                    }
                }
            }
        }

        // ASK AI TO ANALYZE CODE button — Calls Gemini AI API
        binding.btnLabAskAiAnalyze.setOnClickListener {
            val q = currentQuestion ?: return@setOnClickListener
            val sourceCode = binding.etCodeEditor.text?.toString().orEmpty().trim()
            if (sourceCode.isBlank()) {
                android.widget.Toast.makeText(
                    this,
                    "Please write code before asking AI to analyze.",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val progressDialog = MaterialAlertDialogBuilder(this)
                .setTitle("AI Code Analysis")
                .setMessage("Analyzing ${selectedLanguage.displayName} solution for ${q.id} (${q.title})...\nChecking time complexity, edge cases, and potential bugs.")
                .setCancelable(false)
                .show()

            lifecycleScope.launch {
                val learner = if (userId > 0L) {
                    val user = app.authRepository.getLoggedInUser()
                    val userSolvedCount = app.learningRepository.progressDaoDirect().getSolvedQuestions(userId).size
                    user?.let { u ->
                        com.example.data.remote.LearnerProfileContext(
                            name = u.fullName,
                            email = u.email,
                            interests = u.interests.ifBlank { "Arrays, Dynamic Programming, Graphs" },
                            level = u.level,
                            xp = u.xp,
                            streak = u.currentStreak,
                            solvedCount = userSolvedCount,
                            preferredLanguage = selectedLanguage.displayName
                        )
                    }
                } else null

                val result = app.aiRepository.analyzeCode(
                    questionId = q.id,
                    language = selectedLanguage.displayName,
                    sourceCode = sourceCode,
                    learner = learner
                )
                if (!isFinishing && !isDestroyed && progressDialog.isShowing) {
                    progressDialog.dismiss()
                }

                result.onSuccess { analysis ->
                    MaterialAlertDialogBuilder(this@CodingLabActivity)
                        .setTitle("AI Code Analysis")
                        .setMessage(analysis)
                        .setPositiveButton("Got It", null)
                        .show()
                }.onFailure { error ->
                    MaterialAlertDialogBuilder(this@CodingLabActivity)
                        .setTitle("AI Code Analyzer")
                        .setMessage(
                            error.message ?: "Unable to generate AI analysis right now. Please check your internet connection and try again."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }

        // Problem Guide (Collapsible for non-tech students)
        var isGuideExpanded = true
        binding.layoutGuideHeader.setOnClickListener {
            isGuideExpanded = !isGuideExpanded
            binding.layoutGuideBody.visibility = if (isGuideExpanded) View.VISIBLE else View.GONE
            binding.tvGuideToggleLabel.text = if (isGuideExpanded) "Hide Details ▲" else "Show Details ▼"
        }

        var isHintVisible = false
        binding.btnLabToggleHint.setOnClickListener {
            isHintVisible = !isHintVisible
            binding.tvLabGuideHint.visibility = if (isHintVisible) View.VISIBLE else View.GONE
            binding.btnLabToggleHint.text = if (isHintVisible) "💡 Hide Hint / संकेत छुपाएं" else "💡 Show Hint / संकेत देखें"
        }

        lifecycleScope.launch {
            val question = app.learningRepository.getQuestionById(questionId)
            if (question != null) {
                currentQuestion = question
                binding.tvLabProblemTitle.text = question.title
                binding.tvLabProblemMeta.text =
                    "${question.id} • ${question.difficulty} • ${question.topic}"

                // Populate Problem Guide for non-tech students
                binding.tvLabGuideDescription.text = question.description
                binding.tvLabGuideInput.text = question.expectedInput.ifBlank { "None (No input needed)" }
                binding.tvLabGuideOutput.text = question.expectedOutput.ifBlank { "Result" }
                binding.tvLabGuideExample.text = question.examplesJson.ifBlank { "Sample Input: ${question.expectedInput} -> Expected Output: ${question.expectedOutput}" }
                binding.tvLabGuideHint.text = "💡 Hint: ${question.hintsJson.ifBlank { "Break the problem down into simple steps." }}"

                binding.etLabCustomInput.setText(question.expectedInput)
                binding.tvLabInputReset.setOnClickListener {
                    binding.etLabCustomInput.setText(question.expectedInput)
                    android.widget.Toast.makeText(this@CodingLabActivity, "Sample input restored!", android.widget.Toast.LENGTH_SHORT).show()
                }
                switchLanguage(SupportedLanguage.KOTLIN)
                binding.tvLabConsoleOutput.text =
                    "Problem ${question.id} (${question.title}) loaded.\n" +
                        "• Sample Input: ${question.expectedInput}\n" +
                        "• Expected Output: ${question.expectedOutput}\n\n" +
                        "Aap code likh kar seedhe 'Run' ya 'Submit' dabayein. Console aapko input, expected output aur aapke code ka output clear dikhayega."
            }
        }
    }

    private fun switchLanguage(language: SupportedLanguage) {
        selectedLanguage = language
        binding.tvLabEditorFilename.text = "Solution.${language.fileExtension}"
        val question = currentQuestion ?: return
        val existingDraft = codeDraftsByLanguage[language]
        val codeToDisplay = existingDraft ?: QuestionBankSeed.getStarterCodeForLanguage(
            question.starterCodeJson,
            language.displayName
        ).also {
            codeDraftsByLanguage[language] = it
        }
        setEditorText(codeToDisplay)
    }

    private fun setEditorText(code: String) {
        isUpdatingEditorProgrammatically = true
        binding.etCodeEditor.setText(code)
        binding.tvLabCharCount.text = "${code.lines().size} lines • ${code.length} chars"
        isUpdatingEditorProgrammatically = false
    }

    private fun setupSymbolChips() {
        fun insertSymbol(symbol: String) {
            val start = binding.etCodeEditor.selectionStart.coerceAtLeast(0)
            val end = binding.etCodeEditor.selectionEnd.coerceAtLeast(start)
            binding.etCodeEditor.text?.replace(start, end, symbol)
            val offset = if (symbol in listOf("{ }", "( )", "[ ]", "\"\"")) 1 else symbol.length
            val newCursor = (start + offset).coerceAtMost(binding.etCodeEditor.text?.length ?: 0)
            binding.etCodeEditor.setSelection(newCursor)
        }

        binding.chipSymBraces.setOnClickListener { insertSymbol("{ }") }
        binding.chipSymParens.setOnClickListener { insertSymbol("( )") }
        binding.chipSymBrackets.setOnClickListener { insertSymbol("[ ]") }
        binding.chipSymSemicolon.setOnClickListener { insertSymbol(";\n") }
        binding.chipSymArrow.setOnClickListener { insertSymbol(" -> ") }
        binding.chipSymEquals.setOnClickListener { insertSymbol(" == ") }
        binding.chipSymQuotes.setOnClickListener { insertSymbol("\"\"") }
        binding.chipSymTab.setOnClickListener { insertSymbol("    ") }
    }

    private fun formatSourceCode(code: String): String {
        val lines = code.lines()
        val formatted = StringBuilder()
        var indentLevel = 0
        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                formatted.append("\n")
                continue
            }
            if (trimmed.startsWith("}") || trimmed.startsWith(")") || trimmed.startsWith("]")) {
                indentLevel = (indentLevel - 1).coerceAtLeast(0)
            }
            formatted.append("    ".repeat(indentLevel)).append(trimmed).append("\n")
            val openCount = trimmed.count { it == '{' || it == '(' || it == '[' }
            val closeCount = trimmed.count { it == '}' || it == ')' || it == ']' }
            if (openCount > closeCount) {
                indentLevel += (openCount - closeCount)
            }
        }
        return formatted.toString().trimEnd()
    }

    private data class SyntaxCheckResult(val isValid: Boolean, val summary: String)

    private fun checkSyntaxAndBrackets(code: String): SyntaxCheckResult {
        if (code.isBlank()) return SyntaxCheckResult(false, "Code is empty.")
        var curly = 0
        var round = 0
        var square = 0
        var doubleQuotes = 0
        val lines = code.lines()
        for (line in lines) {
            val clean = if (line.contains("//")) line.substringBefore("//") else if (line.contains("#")) line.substringBefore("#") else line
            for (ch in clean) {
                when (ch) {
                    '{' -> curly++
                    '}' -> curly--
                    '(' -> round++
                    ')' -> round--
                    '[' -> square++
                    ']' -> square--
                    '"' -> doubleQuotes++
                }
            }
        }

        val errors = mutableListOf<String>()
        if (curly > 0) errors.add("$curly unclosed '{' brace(s)")
        if (curly < 0) errors.add("${-curly} extra closing '}' brace(s)")
        if (round > 0) errors.add("$round unclosed '(' parenthesis(es)")
        if (round < 0) errors.add("${-round} extra closing ')' parenthesis(es)")
        if (square > 0) errors.add("$square unclosed '[' bracket(s)")
        if (square < 0) errors.add("${-square} extra closing ']' bracket(s)")
        if (doubleQuotes % 2 != 0) errors.add("Unbalanced double-quote string literal")

        return if (errors.isEmpty()) {
            SyntaxCheckResult(
                true,
                "✓ Syntax Structure Verified!\n• All braces { }, parentheses ( ), brackets [ ], and string quotes match.\n• Total Lines: ${lines.size}\n• Language: ${selectedLanguage.displayName}"
            )
        } else {
            SyntaxCheckResult(
                false,
                "⚠ Syntax Notice:\n" + errors.joinToString("\n• ", prefix = "• ") +
                    "\n\nReview the highlighted structural elements in your ${selectedLanguage.displayName} code."
            )
        }
    }

    companion object {
        private const val EXTRA_QUESTION_ID = "extra_question_id"

        fun createIntent(context: Context, questionId: String): Intent {
            return Intent(context, CodingLabActivity::class.java).apply {
                putExtra(EXTRA_QUESTION_ID, questionId)
            }
        }
    }
}
