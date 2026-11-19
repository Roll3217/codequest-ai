package com.example.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.CodeQuestApp
import com.example.MainActivity
import com.example.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    private var hasNavigatedToMain: Boolean = false

    private val viewModel: AuthViewModel by viewModels {
        AuthViewModel.Factory((application as CodeQuestApp).authRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as CodeQuestApp
        app.sessionPreferences.applySavedTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateThemeLabel(app)
        setupKeyboardFocusScroll()

        binding.btnLoginTheme.setOnClickListener {
            app.sessionPreferences.cycleThemeMode()
            updateThemeLabel(app)
        }

        binding.etLoginEmail.doAfterTextChanged {
            binding.tilLoginEmail.error = null
            viewModel.clearError()
        }

        binding.etLoginPassword.doAfterTextChanged {
            binding.tilLoginPassword.error = null
            viewModel.clearError()
        }

        binding.etLoginPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptLogin()
                true
            } else {
                false
            }
        }

        binding.btnLoginSubmit.setOnClickListener {
            attemptLogin()
        }

        binding.btnForgotPassword.setOnClickListener {
            showForgotPasswordDialog(app)
        }

        binding.btnGoToSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is AuthUiState.Idle -> {
                            binding.btnLoginSubmit.isEnabled = true
                            binding.btnLoginSubmit.setText(com.example.R.string.btn_login)
                            binding.cardLoginError.visibility = View.GONE
                        }
                        is AuthUiState.Loading -> {
                            binding.btnLoginSubmit.isEnabled = false
                            binding.btnLoginSubmit.text = "Signing In..."
                            binding.cardLoginError.visibility = View.GONE
                        }
                        is AuthUiState.ValidationError -> {
                            binding.btnLoginSubmit.isEnabled = true
                            binding.btnLoginSubmit.setText(com.example.R.string.btn_login)
                            binding.cardLoginError.visibility = View.VISIBLE
                            binding.tvLoginError.text = state.message
                            binding.loginScrollView.post {
                                binding.loginScrollView.smoothScrollTo(
                                    0,
                                    (binding.cardLoginError.top - 24).coerceAtLeast(0)
                                )
                            }
                            com.google.android.material.snackbar.Snackbar
                                .make(binding.root, state.message, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                                .show()
                        }
                        is AuthUiState.Authenticated -> {
                            if (hasNavigatedToMain) return@collect
                            hasNavigatedToMain = true
                            lifecycleScope.launch {
                                try {
                                    app.notificationService.triggerLoginNotifications(
                                        context = applicationContext,
                                        user = state.user
                                    )
                                } catch (_: Exception) {}
                                val intent = Intent(this@LoginActivity, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                }
                                startActivity(intent)
                                finish()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun attemptLogin() {
        val email = binding.etLoginEmail.text?.toString().orEmpty()
        val password = binding.etLoginPassword.text?.toString().orEmpty()
        viewModel.login(email, password)
    }

    private fun setupKeyboardFocusScroll() {
        val focusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.postDelayed({
                    val targetView = (view.parent?.parent as? View) ?: (view.parent as? View) ?: view
                    var top = 0
                    var current: View? = targetView
                    while (current != null && current != binding.loginScrollView) {
                        top += current.top
                        current = current.parent as? View
                    }
                    binding.loginScrollView.smoothScrollTo(0, (top - 48).coerceAtLeast(0))
                }, 150L)
            }
        }
        binding.etLoginEmail.onFocusChangeListener = focusListener
        binding.etLoginPassword.onFocusChangeListener = focusListener
    }

    private fun showForgotPasswordDialog(app: CodeQuestApp) {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = "user@gmail.com"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            val currentTyped = binding.etLoginEmail.text?.toString().orEmpty()
            if (currentTyped.isNotBlank()) setText(currentTyped)
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(com.example.R.string.dialog_forgot_password_title))
            .setMessage(getString(com.example.R.string.dialog_forgot_password_msg))
            .setView(container)
            .setPositiveButton(getString(com.example.R.string.btn_send_reset_link)) { _, _ ->
                val enteredEmail = input.text?.toString().orEmpty().trim()
                lifecycleScope.launch {
                    when (val res = app.authRepository.requestPasswordReset(enteredEmail)) {
                        is com.example.data.repository.AuthResult.Success -> {
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@LoginActivity)
                                .setTitle("Reset Instructions Sent")
                                .setMessage("Password reset instructions have been dispatched for ${res.user.email}. Check your inbox.")
                                .setPositiveButton("OK", null)
                                .show()
                        }
                        is com.example.data.repository.AuthResult.Error -> {
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@LoginActivity)
                                .setTitle("Password Reset")
                                .setMessage(res.message)
                                .setPositiveButton("OK", null)
                                .show()
                        }
                    }
                }
            }
            .setNegativeButton(getString(com.example.R.string.action_cancel), null)
            .show()
    }

    private fun updateThemeLabel(app: CodeQuestApp) {
        binding.btnLoginTheme.text = "Theme: ${app.sessionPreferences.themeMode.storageKey}"
    }
}
