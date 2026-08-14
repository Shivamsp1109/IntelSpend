package com.spendwise.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.data.local.RecurringEntryDao
import com.spendwise.data.local.toDomain
import com.spendwise.data.local.toEntity
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.RecurringCadence
import com.spendwise.domain.model.RecurringCandidate
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.model.RecurringSchedule
import com.spendwise.domain.model.RecurringStatus
import com.spendwise.domain.model.RecurringType
import com.spendwise.domain.model.TransactionNature
import com.spendwise.domain.model.monthlyEquivalent
import com.spendwise.domain.usecase.ConfirmRecurringCandidateUseCase
import com.spendwise.domain.usecase.DetectRecurringPaymentsUseCase
import com.spendwise.domain.usecase.DismissRecurringCandidateUseCase
import com.spendwise.domain.usecase.ReconcileRecurringPaymentsUseCase
import com.spendwise.domain.usecase.UpdateRecurringStatusUseCase
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
 * Everything comes from Flows, so accepting a suggestion moves it from one list
 * to the other without a refresh, and dismissing one takes it away for good —
 * the detector reads the same dismissal table this writes to.
 */
@HiltViewModel
class RecurringViewModel @Inject constructor(
    private val recurringEntryDao: RecurringEntryDao,
    private val confirmRecurringCandidate: ConfirmRecurringCandidateUseCase,
    private val dismissRecurringCandidate: DismissRecurringCandidateUseCase,
    private val updateRecurringStatus: UpdateRecurringStatusUseCase,
    private val reconcileRecurringPayments: ReconcileRecurringPaymentsUseCase,
    detectRecurringPayments: DetectRecurringPaymentsUseCase
) : ViewModel() {

    private val filter = MutableStateFlow(RecurringFilter.Detected)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val tracked = recurringEntryDao.observeRecurring()
        .map { entities -> entities.map { it.toDomain() } }

    val uiState: StateFlow<RecurringUiState> =
        combine(detectRecurringPayments(), tracked, filter) { candidates, entries, active ->
            val live = entries.filter { it.isLive }
            RecurringUiState(
                filter = active,
                candidates = candidates,
                tracked = entries.sortedByDescending { it.monthlyEquivalent },
                // Normalised before summing: a weekly ₹1,000 and a yearly ₹12,000
                // are not ₹13,000 a month, and adding them raw would say so.
                monthlyCommitment = live.sumOf { it.monthlyEquivalent },
                isLoaded = true
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecurringUiState())

    init {
        // Catches up any payments that landed since the last visit, so a due date
        // shown here is never one the user has already settled.
        viewModelScope.launch { runCatching { reconcileRecurringPayments() } }
    }

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

    fun setStatus(entry: RecurringEntry, status: RecurringStatus) {
        viewModelScope.launch {
            updateRecurringStatus(entry, status)
            _message.value = when (status) {
                RecurringStatus.ACTIVE -> "Resumed ${entry.title}."
                RecurringStatus.PAUSED -> "Paused ${entry.title}."
                RecurringStatus.ENDED -> "Ended ${entry.title}."
            }
        }
    }

    fun delete(entry: RecurringEntry) {
        viewModelScope.launch {
            recurringEntryDao.deleteRecurring(entry.toEntity())
            _message.value = "Removed ${entry.title}."
        }
    }

    /**
     * Saves a commitment the user entered or edited themselves.
     *
     * A hand-entered commitment is a statement of fact rather than a guess, so it
     * keeps full confidence and no occurrence count — there is no detection
     * behind it to describe.
     */
    fun save(draft: RecurringDraft) {
        viewModelScope.launch {
            val amount = draft.amount.toDoubleOrNull()
            if (draft.title.isBlank() || amount == null || amount <= 0) {
                _message.value = "Give it a name and an amount above zero."
                return@launch
            }

            val existing = draft.id?.let { recurringEntryDao.getById(it)?.toDomain() }
            val entry = RecurringEntry(
                id = draft.id ?: 0,
                title = draft.title.trim(),
                amount = amount,
                cadence = draft.cadence,
                type = draft.type,
                currency = draft.currency,
                nature = draft.nature,
                category = draft.category,
                source = existing?.source ?: com.spendwise.domain.model.RecurringSource.MANUAL,
                occurrenceCount = existing?.occurrenceCount ?: 0,
                confidence = existing?.confidence ?: 1.0,
                status = existing?.status ?: RecurringStatus.ACTIVE,
                lastOccurrenceDate = existing?.lastOccurrenceDate,
                // Recomputed on save, because the cadence or the anchor day may be
                // exactly what the user came here to correct.
                nextDueDate = existing?.lastOccurrenceDate?.let {
                    RecurringSchedule.nextDueDateMillis(it, draft.cadence, draft.dueDayOfMonth)
                },
                dueDayOfMonth = draft.dueDayOfMonth
            ).toEntity().copy(isSynced = false)

            if (draft.id == null) {
                recurringEntryDao.insertRecurring(entry)
                _message.value = "Added ${entry.title}."
            } else {
                recurringEntryDao.updateRecurring(entry)
                _message.value = "Updated ${entry.title}."
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

enum class RecurringFilter(val label: String) {
    Detected("Found"),
    Active("Active"),
    Paused("Paused"),
    Ended("Ended")
}

/** The editable shape of a commitment, so the form can hold partial input. */
data class RecurringDraft(
    val id: Int? = null,
    val title: String = "",
    val amount: String = "",
    val cadence: RecurringCadence = RecurringCadence.MONTHLY,
    val type: RecurringType = RecurringType.FIXED,
    val currency: Currency = Currency.INR,
    val nature: TransactionNature = TransactionNature.Spending,
    val category: ExpenseCategory = ExpenseCategory.Other,
    val dueDayOfMonth: Int? = null
) {
    companion object {
        fun from(entry: RecurringEntry) = RecurringDraft(
            id = entry.id,
            title = entry.title,
            amount = entry.amount.toString(),
            cadence = entry.cadence,
            type = entry.type,
            currency = entry.currency,
            nature = entry.nature,
            category = entry.category,
            dueDayOfMonth = entry.dueDayOfMonth
        )
    }
}

data class RecurringUiState(
    val filter: RecurringFilter = RecurringFilter.Detected,
    val candidates: List<RecurringCandidate> = emptyList(),
    val tracked: List<RecurringEntry> = emptyList(),
    /** What every live commitment costs in an average month, cadences normalised. */
    val monthlyCommitment: Double = 0.0,
    /** Distinguishes "still working it out" from "found nothing", which read very differently. */
    val isLoaded: Boolean = false
) {
    fun entriesFor(filter: RecurringFilter): List<RecurringEntry> = when (filter) {
        RecurringFilter.Detected -> emptyList()
        RecurringFilter.Active -> tracked.filter { it.status == RecurringStatus.ACTIVE }
        RecurringFilter.Paused -> tracked.filter { it.status == RecurringStatus.PAUSED }
        RecurringFilter.Ended -> tracked.filter { it.status == RecurringStatus.ENDED }
    }
}
