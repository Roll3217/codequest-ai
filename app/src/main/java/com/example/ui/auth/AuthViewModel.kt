package com.example.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.repository.AuthRepository
import com.example.data.repository.AuthResult
import com.example.model.UserEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AuthUiState {
    data object Idle : AuthUiState()
    data object Loading : AuthUiState()
    data class Authenticated(val user: UserEntity) : AuthUiState()
    data class ValidationError(val message: String) : AuthUiState()
}

class AuthViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun login(email: String, password: String) {
        _uiState.value = AuthUiState.Loading
        viewModelScope.launch {
            when (val result = authRepository.login(email, password)) {
                is AuthResult.Success -> _uiState.value = AuthUiState.Authenticated(result.user)
                is AuthResult.Error -> _uiState.value = AuthUiState.ValidationError(result.message)
            }
        }
    }

    fun signUp(
        fullName: String,
        email: String,
        password: String,
        confirmPassword: String,
        avatarKey: String,
        isCustomPhoto: Boolean = false,
        interests: String = "Arrays, Dynamic Programming, Graphs",
        preferredLanguage: String = "Kotlin"
    ) {
        _uiState.value = AuthUiState.Loading
        viewModelScope.launch {
            when (
                val result = authRepository.signUp(
                    fullName = fullName,
                    email = email,
                    password = password,
                    confirmPassword = confirmPassword,
                    avatarKey = avatarKey,
                    isCustomPhoto = isCustomPhoto,
                    interests = interests,
                    preferredLanguage = preferredLanguage
                )
            ) {
                is AuthResult.Success -> _uiState.value = AuthUiState.Authenticated(result.user)
                is AuthResult.Error -> _uiState.value = AuthUiState.ValidationError(result.message)
            }
        }
    }

    fun clearError() {
        if (_uiState.value is AuthUiState.ValidationError) {
            _uiState.value = AuthUiState.Idle
        }
    }

    class Factory(private val authRepository: AuthRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AuthViewModel(authRepository) as T
        }
    }
}
