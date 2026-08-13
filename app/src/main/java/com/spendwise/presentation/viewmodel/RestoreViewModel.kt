package com.spendwise.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.domain.usecase.RestoreFromServerUseCase
import com.spendwise.domain.usecase.RestoreOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Rebuilds this device's data from the server when it has none.
 *
 * Runs once per launch, from the splash screen, after sign-in has resolved.
 * Split out from the auth view model because the two have different lifetimes:
 * auth state is watched for as long as the app runs, while this is a one-shot
 * that either finds an empty database or does nothing.
 */
@HiltViewModel
class RestoreViewModel @Inject constructor(
    private val restoreFromServerUseCase: RestoreFromServerUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val state: StateFlow<RestoreState> = _state.asStateFlow()

    private var started = false

    /**
     * Safe to call on every recomposition — the guard means the work happens
     * once, rather than restarting each time the splash screen redraws.
     */
    fun restoreIfNeeded() {
        if (started) return
        started = true

        viewModelScope.launch {
            _state.value = RestoreState.Working
            _state.value = when (val outcome = restoreFromServerUseCase()) {
                is RestoreOutcome.Restored -> RestoreState.Done(outcome.total)
                // A failure is not surfaced as an error: the user is signing in,
                // not asking for a restore, and the app works without it. The
                // next launch tries again while the database is still empty.
                RestoreOutcome.NotNeeded, RestoreOutcome.Failed -> RestoreState.Done(0)
            }
        }
    }
}

sealed class RestoreState {
    data object Idle : RestoreState()
    data object Working : RestoreState()
    data class Done(val restoredCount: Int) : RestoreState()
}
