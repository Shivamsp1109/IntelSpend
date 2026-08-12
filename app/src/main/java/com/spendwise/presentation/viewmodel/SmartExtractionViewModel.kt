package com.spendwise.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.data.remote.ExtractionDataSource
import com.spendwise.util.NarrativePreferenceStore
import com.spendwise.util.SmartExtractionPreferenceStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SmartExtractionUiState(
    val enabled: Boolean = false,
    /** Written summaries — a separate opt-in, because it sends different data. */
    val narrativeEnabled: Boolean = false,
    val callsThisMonth: Int = 0,
    val monthlyCallCap: Int = 0,
    val estimatedCostUsd: Double = 0.0,
    val usageLoaded: Boolean = false
)

@HiltViewModel
class SmartExtractionViewModel @Inject constructor(
    private val preferences: SmartExtractionPreferenceStore,
    private val narrativePreferences: NarrativePreferenceStore,
    private val extractionDataSource: ExtractionDataSource
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SmartExtractionUiState(
            enabled = preferences.enabled.value,
            narrativeEnabled = narrativePreferences.enabled.value
        )
    )
    val uiState: StateFlow<SmartExtractionUiState> = _uiState.asStateFlow()

    init {
        // Both features bill against the same monthly allowance, so either one
        // being on is reason enough to show where it stands.
        if (preferences.enabled.value || narrativePreferences.enabled.value) refreshUsage()
    }

    fun setEnabled(enabled: Boolean) {
        preferences.setEnabled(enabled)
        _uiState.update { it.copy(enabled = enabled) }
        if (enabled) refreshUsage()
    }

    fun setNarrativeEnabled(enabled: Boolean) {
        narrativePreferences.setEnabled(enabled)
        _uiState.update { it.copy(narrativeEnabled = enabled) }
        if (enabled) refreshUsage()
    }

    /** Usage lives on the server, so this is best-effort — a failure just leaves the row blank. */
    fun refreshUsage() {
        viewModelScope.launch {
            val usage = extractionDataSource.usage() ?: return@launch
            _uiState.update {
                it.copy(
                    callsThisMonth = usage.callsThisMonth,
                    monthlyCallCap = usage.monthlyCallCap,
                    estimatedCostUsd = usage.estimatedCostUsd,
                    usageLoaded = true
                )
            }
        }
    }
}
