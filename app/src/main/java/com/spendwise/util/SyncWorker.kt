package com.spendwise.util

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spendwise.domain.repository.ExpenseRepository
import com.spendwise.domain.repository.GoalRepository
import com.spendwise.domain.repository.IncomeRepository
import com.spendwise.domain.repository.RecurringEntryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Unified sync worker that flushes all pending local-only rows to the
 * remote MySQL backend in a single job.
 *
 * Each entity type is synced independently — a failure in one (e.g. a
 * transient server error for goals) does not prevent the others from
 * uploading. The worker returns [Result.retry()] if **any** entity sync
 * throws, so WorkManager will reschedule the entire job and catch whatever
 * was left behind.
 *
 * Replaces the old [ExpenseSyncWorker]-only pattern. [ExpenseSyncWorker]
 * is kept alive for the immediate-on-write fast path in
 * [ExpenseSyncScheduler]; this worker handles the periodic catch-all sweep.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val expenseRepository: ExpenseRepository,
    private val incomeRepository: IncomeRepository,
    private val goalRepository: GoalRepository,
    private val recurringRepository: RecurringEntryRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        var anyFailure = false

        runCatching { expenseRepository.syncPendingExpenses() }
            .onFailure { Log.w(TAG, "Expense sync failed.", it); anyFailure = true }

        runCatching { incomeRepository.syncPendingIncomes() }
            .onFailure { Log.w(TAG, "Income sync failed.", it); anyFailure = true }

        runCatching { goalRepository.syncPendingGoals() }
            .onFailure { Log.w(TAG, "Goal sync failed.", it); anyFailure = true }

        runCatching { recurringRepository.syncPendingRecurring() }
            .onFailure { Log.w(TAG, "Recurring sync failed.", it); anyFailure = true }

        return if (anyFailure) {
            Log.w(TAG, "One or more entity syncs failed; scheduling retry.")
            Result.retry()
        } else {
            Result.success()
        }
    }

    private companion object {
        const val TAG = "SyncWorker"
    }
}
