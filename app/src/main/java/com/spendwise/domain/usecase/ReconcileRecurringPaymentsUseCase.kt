package com.spendwise.domain.usecase

import android.util.Log
import com.spendwise.data.local.ExpenseDao
import com.spendwise.data.local.RecurringEntryDao
import com.spendwise.data.local.RecurringExpenseCrossRef
import com.spendwise.data.local.toDomain
import com.spendwise.domain.model.RecurringType
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        // Matching compares every live commitment against every unattributed
        // payment, which after an import is a large multiplication. Callers
        // include a view model, whose scope runs on the main dispatcher, so
        // leaving it there would freeze the screen that started it.
        val matches = withContext(Dispatchers.Default) {
            RecurringMatcher.match(entries, unlinked)
        }

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
                notePriceChange(match, unlinked)
            }.onFailure {
                Log.w(TAG, "Could not reconcile ${match.entry.title}.", it)
            }
        }

        return matches.sumOf { it.expenseIds.size }
    }

    /**
     * Parks a charge that disagrees with the agreed amount, rather than adopting
     * it.
     *
     * The amount on a commitment is a figure the user confirmed, and it is what
     * the app uses to say what they owe each month. A payment that comes in
     * higher is worth knowing about — a subscription going up is one of the few
     * things here anybody would act on — but it is a question to put to them,
     * not a correction to make on their behalf.
     *
     * Only for fixed commitments. On a variable one the amount is expected to
     * move, and every bill would raise a question about nothing.
     */
    private suspend fun notePriceChange(
        match: RecurringMatch,
        unlinked: List<com.spendwise.data.local.ExpenseTimeSeriesRow>
    ) {
        val entry = match.entry
        if (entry.type != RecurringType.FIXED) return

        // The newest payment attributed in this pass is the current charge.
        val latest = unlinked
            .filter { it.expenseId in match.expenseIds }
            .maxByOrNull { it.date }
            ?.amount
            ?: return

        if (isSameCharge(latest, entry.amount)) return
        // Already asked and refused; asking again every month is nagging.
        if (entry.declinedAmount?.let { isSameCharge(latest, it) } == true) return
        if (entry.pendingAmount?.let { isSameCharge(latest, it) } == true) return

        recurringEntryDao.setPendingAmount(entry.id, latest)
    }

    private fun isSameCharge(first: Double, second: Double): Boolean {
        val larger = maxOf(abs(first), abs(second))
        if (larger == 0.0) return true
        return abs(first - second) / larger <= SAME_CHARGE_TOLERANCE
    }

    private companion object {
        const val TAG = "ReconcileRecurring"

        /** Rounding and the odd paisa of proration, not a price change. */
        const val SAME_CHARGE_TOLERANCE = 0.02
    }
}
