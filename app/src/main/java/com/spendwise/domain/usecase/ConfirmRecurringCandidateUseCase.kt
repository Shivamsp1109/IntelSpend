package com.spendwise.domain.usecase

import android.util.Log
import com.spendwise.data.local.RecurringEntryDao
import com.spendwise.data.local.RecurringExpenseCrossRef
import com.spendwise.data.local.toEntity
import com.spendwise.domain.model.RecurringCandidate
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.model.RecurringSchedule
import com.spendwise.domain.model.RecurringSource
import com.spendwise.domain.model.RecurringStatus
import com.spendwise.util.SyncScheduler
import javax.inject.Inject

/**
 * Turns a detected pattern into a commitment the app will track.
 *
 * The payments that produced the candidate are linked to the new entry rather
 * than merely counted. That link is what later lets the app tell whether this
 * month's charge has already gone out — the difference between a useful reminder
 * and one that tells someone to pay a bill they paid last week.
 */
class ConfirmRecurringCandidateUseCase @Inject constructor(
    private val recurringEntryDao: RecurringEntryDao,
    private val syncScheduler: SyncScheduler
) {
    suspend operator fun invoke(candidate: RecurringCandidate): Int {
        val lastOccurrence = candidate.lastOccurrenceDate

        val entry = RecurringEntry(
            title = candidate.merchant,
            amount = candidate.averageAmount,
            cadence = candidate.cadence,
            type = candidate.type,
            currency = candidate.currency,
            nature = candidate.nature,
            category = candidate.category,
            source = RecurringSource.DETECTED,
            occurrenceCount = candidate.occurrenceCount,
            confidence = candidate.confidence,
            status = RecurringStatus.ACTIVE,
            lastOccurrenceDate = lastOccurrence,
            // Projected straight away rather than waiting for the next payment,
            // so a commitment is useful the moment it is accepted instead of
            // going quiet for a cycle.
            nextDueDate = RecurringSchedule.nextDueDateMillis(
                lastOccurrence = lastOccurrence,
                cadence = candidate.cadence,
                dueDayOfMonth = candidate.dueDayOfMonth
            ),
            dueDayOfMonth = candidate.dueDayOfMonth
        )

        val recurringId = recurringEntryDao.insertRecurring(entry.toEntity()).toInt()

        // Best-effort per link: a cross-ref that fails to write costs some
        // history, which is worth strictly less than the commitment itself, and
        // failing the whole confirmation over one row would lose both.
        for (occurrence in candidate.occurrences) {
            runCatching {
                recurringEntryDao.linkExpense(
                    RecurringExpenseCrossRef(recurringId = recurringId, expenseId = occurrence.expenseId)
                )
            }.onFailure { Log.w(TAG, "Could not link expense ${occurrence.expenseId}.", it) }
        }

        runCatching { syncScheduler.enqueueImmediateSync() }
            .onFailure { Log.w(TAG, "Could not schedule a sync after confirming.", it) }

        return recurringId
    }

    private companion object {
        const val TAG = "ConfirmRecurring"
    }
}
