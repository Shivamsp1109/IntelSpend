package com.spendwise.domain.usecase

import com.spendwise.data.local.ExpenseDao
import com.spendwise.data.local.RecurringEntryDao
import com.spendwise.data.local.toDomain
import com.spendwise.domain.model.RecurringCandidate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

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
        ) { rows, tracked, dismissed -> Triple(rows, tracked, dismissed) }
            // Coalesced, because a sync writes many rows in quick succession and
            // each one re-emits. Without this the detector ran once per row and
            // every pass but the last was thrown away unseen.
            .debounce(SETTLE_MILLIS)
            .map { (rows, tracked, dismissed) ->
                RecurringDetector.detect(
                    rows = rows,
                    existing = tracked.map { it.toDomain() },
                    dismissed = dismissed,
                    now = System.currentTimeMillis()
                )
            }
            // Off the main thread. The collector is a view model scope, which
            // runs on the main dispatcher, so without this the clustering,
            // similarity matching and cadence fitting — over the user's whole
            // history — happened on the UI thread. During a large import that
            // was hundreds of passes, and the app stopped responding.
            .flowOn(Dispatchers.Default)

    /** Snapshot form, for the background worker that has no reason to keep observing. */
    suspend fun once(): List<RecurringCandidate> = invoke().first()

    private companion object {
        /** Long enough to let a burst of writes finish, short enough to feel live. */
        const val SETTLE_MILLIS = 300L
    }
}
