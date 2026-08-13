package com.spendwise.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.data.local.RecurringEntryDao
import com.spendwise.data.local.toDomain
import com.spendwise.data.local.toEntity
import com.spendwise.domain.model.RecurringCandidate
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.usecase.ConfirmRecurringCandidateUseCase
import com.spendwise.domain.usecase.DetectRecurringPaymentsUseCase
import com.spendwise.domain.usecase.DismissRecurringCandidateUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the recurring-payments screen.
 *
 * Both lists come from Flows, so accepting a suggestion moves it from one to the
 * other without a refresh — and dismissing one takes it away for good, because
 * the detector reads the same dismissal table it writes to.
 */
@HiltViewModel
class RecurringViewModel @Inject constructor(
    private val recurringEntryDao: RecurringEntryDao,
    private val confirmRecurringCandidate: ConfirmRecurringCandidateUseCase,
    private val dismissRecurringCandidate: DismissRecurringCandidateUseCase,
    detectRecurringPayments: DetectRecurringPaymentsUseCase
) : ViewModel() {

    private val filter = MutableStateFlow(RecurringFilter.Detected)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val tracked = recurringEntryDao.observeRecurring()
        .map { entities -> entities.map { it.toDomain() } }

    val uiState: StateFlow<RecurringUiState> =
        combine(detectRecurringPayments(), tracked, filter) { candidates, entries, active ->
            RecurringUiState(
                filter = active,
                candidates = candidates,
                tracked = entries.sortedByDescending { it.amount },
                isLoaded = true
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecurringUiState())

    fun setFilter(value: RecurringFilter) {
        filter.value = value
    }

    fun confirm(candidate: RecurringCandidate) {
        viewModelScope.launch {
            confirmRecurringCandidate(candidate)
            _message.value = "Now tracking ${candidate.merchant}."
        }
    }

    fun dismiss(candidate: RecurringCandidate) {
        viewModelScope.launch {
            dismissRecurringCandidate(candidate)
            _message.value = "Ignored ${candidate.merchant}."
        }
    }

    fun delete(entry: RecurringEntry) {
        viewModelScope.launch {
            recurringEntryDao.deleteRecurring(entry.toEntity())
            _message.value = "Removed ${entry.title}."
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

enum class RecurringFilter(val label: String) {
    Detected("Found"),
    Tracked("Tracking")
}

data class RecurringUiState(
    val filter: RecurringFilter = RecurringFilter.Detected,
    val candidates: List<RecurringCandidate> = emptyList(),
    val tracked: List<RecurringEntry> = emptyList(),
    /** Distinguishes "still working it out" from "found nothing", which read very differently. */
    val isLoaded: Boolean = false
)
