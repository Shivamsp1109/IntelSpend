package com.spendwise.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.domain.model.AnalyticsPeriod
import com.spendwise.domain.model.FinancialHealthSnapshot
import com.spendwise.domain.repository.AnalyticsRepository
import com.spendwise.domain.usecase.ComputeFinancialHealthUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * Drives the financial health screen.
 *
 * Recomputed off the repository's change signal so the assessment moves when a
 * transaction is added, but debounced first: an import writes hundreds of rows
 * in a burst and each one would otherwise trigger a full round of aggregate
 * queries, goal arithmetic and commitment projection. Settled on a background
 * dispatcher for the same reason — this is the heaviest read in the app and it
 * has no business running on the thread that draws the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FinancialHealthViewModel @Inject constructor(
    private val computeFinancialHealth: ComputeFinancialHealthUseCase,
    analyticsRepository: AnalyticsRepository
) : ViewModel() {

    private val period = MutableStateFlow(AnalyticsPeriod.thisMonth())
    val selectedPeriod: StateFlow<AnalyticsPeriod> = period.asStateFlow()

    val uiState: StateFlow<FinancialHealthUiState> =
        combine(analyticsRepository.changes(), period) { _, window -> window }
            .debounce(SETTLE_MILLIS)
            .mapLatest { window ->
                runCatching { computeFinancialHealth(window) }
                    .fold(
                        onSuccess = { FinancialHealthUiState(snapshot = it, isLoaded = true) },
                        onFailure = { error ->
                            Log.w(TAG, "Could not assess financial health.", error)
                            FinancialHealthUiState(
                                isLoaded = true,
                                error = "Could not work out your position just now."
                            )
                        }
                    )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                FinancialHealthUiState()
            )

    fun showPreviousPeriod() {
        period.value = period.value.shifted(-1)
    }

    /** Refused rather than shown empty: a month that has not started has nothing to assess. */
    fun showNextPeriod() {
        val next = period.value.shifted(1)
        if (!next.startsInTheFuture()) period.value = next
    }

    private companion object {
        const val TAG = "FinancialHealthViewModel"

        /** Long enough for a burst of writes to finish, short enough to feel live. */
        const val SETTLE_MILLIS = 300L
    }
}

data class FinancialHealthUiState(
    val snapshot: FinancialHealthSnapshot? = null,
    val isLoaded: Boolean = false,
    val error: String? = null
)
