package com.spendwise.data.ingestion.duplicate

import com.spendwise.data.ingestion.model.RawTransaction
import com.spendwise.data.local.ExpenseDao
import com.spendwise.data.local.IncomeDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Loads the transactions an import could collide with and hands them to
 * [DuplicateMatcher], which holds the actual rules.
 *
 * The window is widened by the matcher's tolerance on both ends, because a
 * payment made on the first day of a statement may have been recorded from a
 * screenshot a few days earlier.
 */
class DuplicateChecker @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val incomeDao: IncomeDao
) {
    suspend fun flagDuplicates(transactions: List<RawTransaction>) = withContext(Dispatchers.IO) {
        if (transactions.isEmpty()) return@withContext

        val window = DuplicateMatcher.DATE_WINDOW_MILLIS
        val from = transactions.minOf { it.date } - window
        val to = transactions.maxOf { it.date } + window

        // Rows whose date was assumed are pulled in regardless of the window.
        // By definition they sit wherever the import happened rather than when
        // the payment did, so the window is the one thing guaranteed not to
        // find them — and they are exactly the rows a later statement needs to
        // match against. Distinct by id, since a row can satisfy both queries.
        val existingExpenses = (
            expenseDao.getExpensesBetweenDates(from, to) + expenseDao.getExpensesWithAssumedDate()
            ).distinctBy { it.id }.map {
            ExistingTransaction(
                it.amount, it.date, it.merchant, it.currency, it.reference, it.title, it.dateIsAssumed
            )
        }

        val existingIncomes = (
            incomeDao.getIncomesBetweenDates(from, to) + incomeDao.getIncomesWithAssumedDate()
            ).distinctBy { it.id }.map {
            // Income has no merchant column; the note carries the payer when
            // the entry came from an import.
            ExistingTransaction(
                it.amount, it.date, it.note, it.currency, it.reference, it.title, it.dateIsAssumed
            )
        }

        DuplicateMatcher.flag(transactions, existingExpenses, existingIncomes)
    }
}
