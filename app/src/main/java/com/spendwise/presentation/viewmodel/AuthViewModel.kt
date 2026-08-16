package com.spendwise.presentation.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.domain.repository.AuthRepository
import com.spendwise.domain.repository.AuthUser
import com.spendwise.domain.repository.Gender
import com.spendwise.domain.usecase.PrepareUserSessionUseCase
import com.spendwise.domain.usecase.SignOutUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val prepareUserSession: PrepareUserSessionUseCase,
    private val signOutUseCase: SignOutUseCase
) : ViewModel() {
    private val _isAuthResolved = MutableStateFlow(false)

    /** True once the underlying Firebase auth-state listener has fired at least once. */
    val isAuthResolved = _isAuthResolved.asStateFlow()

    val currentUser: StateFlow<AuthUser?> = authRepository.currentUser
        .onEach { _isAuthResolved.value = true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    private val _loginState = MutableStateFlow(LoginUiState())
    val loginState = _loginState.asStateFlow()

    private val _sessionReady = MutableStateFlow(false)

    /**
     * True once this device's data is known to belong to the signed-in account.
     *
     * Nothing may show financial data before this. Firebase reports a signed-in
     * user the instant authentication succeeds, which is well before the
     * previous account's rows have been cleared out of the local database —
     * navigating on the user alone put the new person on a home screen full of
     * somebody else's income and spending while the wipe ran behind it.
     */
    val sessionReady: StateFlow<Boolean> = _sessionReady.asStateFlow()

    init {
        // Driven by the auth state rather than called from each sign-in path.
        // Signing in navigates straight to the home screen and never revisits
        // the splash, so per-screen checks missed account switches made without
        // restarting the app — which is exactly how the leak got in.
        viewModelScope.launch {
            authRepository.currentUser
                .map { it?.id }
                .distinctUntilChanged()
                .collect { uid ->
                    _sessionReady.value = false
                    if (uid != null) {
                        runCatching { prepareUserSession() }
                            .onFailure { Log.w(TAG, "Could not prepare the session.", it) }
                        _sessionReady.value = true
                    }
                }
        }
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _loginState.value = LoginUiState(isLoading = true)
            val result = authRepository.loginWithEmail(email.trim(), password)
            finishSignIn(result)
        }
    }

    fun register(
        email: String,
        password: String,
        name: String,
        gender: Gender?,
        profileImageUri: Uri?
    ) {
        if (name.isBlank()) {
            _loginState.value = LoginUiState(error = "Enter your name.")
            return
        }
        if (gender == null) {
            _loginState.value = LoginUiState(error = "Select your gender.")
            return
        }
        viewModelScope.launch {
            _loginState.value = LoginUiState(isLoading = true)
            val result = authRepository.registerWithEmail(
                email = email.trim(),
                password = password,
                name = name.trim(),
                gender = gender,
                profileImageUri = profileImageUri
            )
            finishSignIn(result)
        }
    }

    fun loginWithGoogleIdToken(idToken: String) {
        viewModelScope.launch {
            _loginState.value = LoginUiState(isLoading = true)
            val result = authRepository.loginWithGoogleIdToken(idToken)
            finishSignIn(result)
        }
    }

    /**
     * Clearing the previous account's data is not done here — it is driven by
     * the auth state above, so every route into a session goes through it rather
     * than only the ones somebody remembered to wire up.
     */
    private fun finishSignIn(result: Result<*>) {
        _loginState.value = LoginUiState(error = result.exceptionOrNull()?.message)
    }

    fun showAuthError(message: String) {
        _loginState.value = LoginUiState(error = message)
    }

    fun logout() {
        viewModelScope.launch {
            signOutUseCase()
        }
    }

    private companion object {
        const val TAG = "AuthViewModel"
    }
}

data class LoginUiState(
    val isLoading: Boolean = false,
    val error: String? = null
)
