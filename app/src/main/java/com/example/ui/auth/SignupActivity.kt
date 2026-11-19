package com.example.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.CodeQuestApp
import com.example.MainActivity
import com.example.R
import com.example.databinding.ActivitySignupBinding
import com.example.databinding.ItemAvatarChoiceBinding
import com.example.model.AvatarOption
import com.example.utils.AvatarHelper
import kotlinx.coroutines.launch

class SignupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding

    private val viewModel: AuthViewModel by viewModels {
        AuthViewModel.Factory((application as CodeQuestApp).authRepository)
    }

    private var selectedAvatarKey: String = AvatarHelper.PREDEFINED_AVATARS.first().id
    private var isCustomGalleryPhoto: Boolean = false
    private lateinit var avatarAdapter: AvatarAdapter

    private var hasNavigatedToMain: Boolean = false

    // Zero-permission Android Photo Picker for custom gallery avatar selection
    private val pickMediaLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    val savedPath = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        AvatarHelper.copyGalleryUriToInternalStorage(applicationContext, uri)
                    }
                    if (isFinishing || isDestroyed) return@launch
                    if (savedPath != null) {
                        selectedAvatarKey = savedPath
                        isCustomGalleryPhoto = true
                        AvatarHelper.loadAvatarInto(binding.ivSelectedAvatarPreview, selectedAvatarKey, true)
                        avatarAdapter.clearPredefinedSelection()
                    } else {
                        com.google.android.material.snackbar.Snackbar
                            .make(
                                binding.root,
                                "Could not load that image. Please try another photo.",
                                com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                            )
                            .show()
                    }
                }
            }
        }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SELECTED_AVATAR, selectedAvatarKey)
        outState.putBoolean(KEY_IS_CUSTOM_PHOTO, isCustomGalleryPhoto)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as CodeQuestApp
        app.sessionPreferences.applySavedTheme()
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState != null) {
            selectedAvatarKey = savedInstanceState.getString(KEY_SELECTED_AVATAR, selectedAvatarKey)
            isCustomGalleryPhoto = savedInstanceState.getBoolean(KEY_IS_CUSTOM_PHOTO, false)
        }

        updateThemeLabel(app)
        setupAvatarRecyclerView()
        if (isCustomGalleryPhoto) {
            AvatarHelper.loadAvatarInto(binding.ivSelectedAvatarPreview, selectedAvatarKey, true)
            avatarAdapter.clearPredefinedSelection()
        }
        setupKeyboardFocusScroll()

        binding.btnSignupTheme.setOnClickListener {
            app.sessionPreferences.cycleThemeMode()
            updateThemeLabel(app)
        }

        binding.btnPickGallery.setOnClickListener {
            try {
                pickMediaLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            } catch (_: Exception) {
                com.google.android.material.snackbar.Snackbar
                    .make(binding.root, "Photo picker is unavailable on this device.", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                    .show()
            }
        }

        binding.etSignupName.doAfterTextChanged { viewModel.clearError() }
        binding.etSignupEmail.doAfterTextChanged { viewModel.clearError() }
        binding.etSignupPassword.doAfterTextChanged { viewModel.clearError() }
        binding.etSignupConfirmPassword.doAfterTextChanged { viewModel.clearError() }

        binding.etSignupConfirmPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptSignup()
                true
            } else {
                false
            }
        }

        binding.btnSignupSubmit.setOnClickListener {
            attemptSignup()
        }

        binding.btnGoToLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is AuthUiState.Idle -> {
                            binding.btnSignupSubmit.isEnabled = true
                            binding.btnSignupSubmit.setText(R.string.btn_signup)
                            binding.cardSignupError.visibility = View.GONE
                        }
                        is AuthUiState.Loading -> {
                            binding.btnSignupSubmit.isEnabled = false
                            binding.btnSignupSubmit.text = "Creating Account..."
                            binding.cardSignupError.visibility = View.GONE
                        }
                        is AuthUiState.ValidationError -> {
                            binding.btnSignupSubmit.isEnabled = true
                            binding.btnSignupSubmit.setText(R.string.btn_signup)
                            binding.cardSignupError.visibility = View.VISIBLE
                            binding.tvSignupError.text = state.message
                            binding.signupScrollView.post {
                                binding.signupScrollView.smoothScrollTo(
                                    0,
                                    (binding.cardSignupError.top - 24).coerceAtLeast(0)
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
                                val intent = Intent(this@SignupActivity, MainActivity::class.java).apply {
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

    private fun setupAvatarRecyclerView() {
        avatarAdapter = AvatarAdapter(
            avatars = AvatarHelper.PREDEFINED_AVATARS,
            initialSelectedId = selectedAvatarKey,
            onAvatarSelected = { option ->
                selectedAvatarKey = option.id
                isCustomGalleryPhoto = false
                binding.ivSelectedAvatarPreview.setImageResource(option.drawableResId)
            }
        )
        binding.rvAvatars.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvAvatars.adapter = avatarAdapter
    }

    private fun getSelectedInterests(): String {
        val selected = mutableListOf<String>()
        val chips = listOf(
            binding.chipInterestArrays,
            binding.chipInterestStrings,
            binding.chipInterestDp,
            binding.chipInterestTrees,
            binding.chipInterestSystemDesign,
            binding.chipInterestPython,
            binding.chipInterestKotlin,
            binding.chipInterestJava,
            binding.chipInterestCpp
        )
        for (chip in chips) {
            if (chip.isChecked) {
                selected.add(chip.text.toString())
            }
        }
        return if (selected.isEmpty()) "Arrays, Dynamic Programming, Graphs" else selected.joinToString(", ")
    }

    private fun attemptSignup() {
        val fullName = binding.etSignupName.text?.toString().orEmpty()
        val email = binding.etSignupEmail.text?.toString().orEmpty()
        val password = binding.etSignupPassword.text?.toString().orEmpty()
        val confirmPassword = binding.etSignupConfirmPassword.text?.toString().orEmpty()
        val interests = getSelectedInterests()
        val preferredLang = if (binding.chipInterestPython.isChecked) "Python" else if (binding.chipInterestCpp.isChecked) "C++" else if (binding.chipInterestJava.isChecked) "Java" else "Kotlin"

        viewModel.signUp(
            fullName = fullName,
            email = email,
            password = password,
            confirmPassword = confirmPassword,
            avatarKey = selectedAvatarKey,
            isCustomPhoto = isCustomGalleryPhoto,
            interests = interests,
            preferredLanguage = preferredLang
        )
    }

    private fun setupKeyboardFocusScroll() {
        val focusListener = View.OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.postDelayed({
                    val targetView = (view.parent?.parent as? View) ?: (view.parent as? View) ?: view
                    var top = 0
                    var current: View? = targetView
                    while (current != null && current != binding.signupScrollView) {
                        top += current.top
                        current = current.parent as? View
                    }
                    binding.signupScrollView.smoothScrollTo(0, (top - 48).coerceAtLeast(0))
                }, 150L)
            }
        }
        binding.etSignupName.onFocusChangeListener = focusListener
        binding.etSignupEmail.onFocusChangeListener = focusListener
        binding.etSignupPassword.onFocusChangeListener = focusListener
        binding.etSignupConfirmPassword.onFocusChangeListener = focusListener
    }

    private fun updateThemeLabel(app: CodeQuestApp) {
        binding.btnSignupTheme.text = "Theme: ${app.sessionPreferences.themeMode.storageKey}"
    }

    private class AvatarAdapter(
        private val avatars: List<AvatarOption>,
        initialSelectedId: String,
        private val onAvatarSelected: (AvatarOption) -> Unit
    ) : RecyclerView.Adapter<AvatarAdapter.AvatarViewHolder>() {

        private var selectedId: String? = initialSelectedId

        inner class AvatarViewHolder(val itemBinding: ItemAvatarChoiceBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        fun clearPredefinedSelection() {
            selectedId = null
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AvatarViewHolder {
            val binding = ItemAvatarChoiceBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return AvatarViewHolder(binding)
        }

        override fun onBindViewHolder(holder: AvatarViewHolder, position: Int) {
            val option = avatars[position]
            val isSelected = option.id == selectedId
            // Always render the actual visual image drawable, never filenames
            holder.itemBinding.ivAvatarOption.setImageResource(option.drawableResId)
            holder.itemBinding.ivAvatarOption.contentDescription = option.title
            holder.itemBinding.avatarItemContainer.setBackgroundResource(
                if (isSelected) R.drawable.bg_avatar_ring_selected else R.drawable.bg_avatar_ring_unselected
            )
            holder.itemBinding.ivAvatarCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

            holder.itemBinding.root.setOnClickListener {
                selectedId = option.id
                notifyDataSetChanged()
                onAvatarSelected(option)
            }
        }

        override fun getItemCount(): Int = avatars.size
    }

    companion object {
        private const val KEY_SELECTED_AVATAR = "key_selected_avatar"
        private const val KEY_IS_CUSTOM_PHOTO = "key_is_custom_photo"
    }
}
