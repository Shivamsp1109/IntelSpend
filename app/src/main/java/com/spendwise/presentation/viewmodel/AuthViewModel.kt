package com.spendwise.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.domain.repository.AuthRepository
import com.spendwise.domain.repository.AuthUser
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {
    val currentUser: StateFlow<AuthUser?> = authRepository.currentUser.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    private val _loginState = MutableStateFlow(LoginUiState())
    val loginState = _loginState.asStateFlow()

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _loginState.value = LoginUiState(isLoading = true)
            val result = authRepository.loginWithEmail(email.trim(), password)
            _loginState.value = LoginUiState(error = result.exceptionOrNull()?.message)
        }
    }

    fun register(email: String, password: String) {
        viewModelScope.launch {
            _loginState.value = LoginUiState(isLoading = true)
            val result = authRepository.registerWithEmail(email.trim(), password)
            _loginState.value = LoginUiState(error = result.exceptionOrNull()?.message)
        }
    }

    fun loginWithGoogleIdToken(idToken: String) {
        viewModelScope.launch {
            _loginState.value = LoginUiState(isLoading = true)
            val result = authRepository.loginWithGoogleIdToken(idToken)
            _loginState.value = LoginUiState(error = result.exceptionOrNull()?.message)
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }
}

data class LoginUiState(
    val isLoading: Boolean = false,
    val error: String? = null
)
