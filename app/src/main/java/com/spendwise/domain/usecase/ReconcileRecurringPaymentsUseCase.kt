package com.spendwise.domain.usecase

import android.util.Log
import com.spendwise.data.local.ExpenseDao
import com.spendwise.data.local.RecurringEntryDao
import com.spendwise.data.local.RecurringExpenseCrossRef
import com.spendwise.data.local.toDomain
import javax.inject.Inject

/**
 * Brings every tracked commitment up to date with the payments that have
 * actually gone out.
 *
 * Run after an import and before reminders are considered. Without it a
 * commitment's due date only ever moves when the user confirms it, so the app
 * would go on insisting rent is due days after it was paid — the single fastest
 * way to make someone turn reminders off.
 *
 * Safely repeatable: it only ever looks at payments not already attributed to
 * something, so running it twice does nothing the second time.
 */
class ReconcileRecurringPaymentsUseCase @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val recurringEntryDao: RecurringEntryDao
) {
    suspend operator fun invoke(): Int {
        val entries = recurringEntryDao.getActiveRecurring().map { it.toDomain() }
        if (entries.isEmpty()) return 0

        val unlinked = expenseDao.getUnlinkedExpenses()
        val matches = RecurringMatcher.match(entries, unlinked)

        for (match in matches) {
            runCatching {
                for (expenseId in match.expenseIds) {
                    recurringEntryDao.linkExpense(
                        RecurringExpenseCrossRef(recurringId = match.entry.id, expenseId = expenseId)
                    )
                }
                recurringEntryDao.recordOccurrence(
                    id = match.entry.id,
                    lastOccurrenceDate = match.lastOccurrenceDate,
                    nextDueDate = match.nextDueDate,
                    additionalOccurrences = match.expenseIds.size
                )
            }.onFailure {
                Log.w(TAG, "Could not reconcile ${match.entry.title}.", it)
            }
        }

        return matches.sumOf { it.expenseIds.size }
    }

    private companion object {
        const val TAG = "ReconcileRecurring"
    }
}
