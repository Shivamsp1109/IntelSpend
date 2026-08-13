package com.spendwise.domain.usecase

import com.spendwise.data.local.ExpenseDao
import com.spendwise.data.local.RecurringEntryDao
import com.spendwise.data.local.toDomain
import com.spendwise.domain.model.RecurringCandidate
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Watches transaction history for payments that repeat.
 *
 * Reactive rather than a one-off scan, so importing a statement immediately
 * changes what is on offer instead of waiting for the next background pass. All
 * of the actual reasoning lives in [RecurringDetector]; this only supplies it
 * with data and a clock.
 */
class DetectRecurringPaymentsUseCase @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val recurringEntryDao: RecurringEntryDao
) {
    operator fun invoke(): Flow<List<RecurringCandidate>> =
        combine(
            expenseDao.observeExpenseTimeSeries(),
            recurringEntryDao.observeRecurring(),
            recurringEntryDao.observeDismissedCandidates()
        ) { rows, tracked, dismissed ->
            RecurringDetector.detect(
                rows = rows,
                existing = tracked.map { it.toDomain() },
                dismissed = dismissed,
                now = System.currentTimeMillis()
            )
        }

    /** Snapshot form, for the background worker that has no reason to keep observing. */
    suspend fun once(): List<RecurringCandidate> = invoke().first()
}
