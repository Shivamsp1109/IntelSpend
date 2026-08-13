package com.spendwise.domain.usecase

import android.util.Log
import com.spendwise.data.local.ExpenseDao
import com.spendwise.data.local.ExpenseEntity
import com.spendwise.data.local.IncomeDao
import com.spendwise.data.local.IncomeEntity
import com.spendwise.data.remote.ExpenseSyncPayload
import com.spendwise.data.remote.IncomeSyncPayload
import com.spendwise.data.remote.MySqlExpenseDataSource
import com.spendwise.data.remote.MySqlIncomeDataSource
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseSource
import javax.inject.Inject

/**
 * Rebuilds a device's local database from what the server holds.
 *
 * For the case where the phone no longer has the data: a reinstall, a new
 * handset, or the recovery path that sets aside a database whose encryption key
 * was lost. Sync is otherwise push-only, so without this the local database is
 * the only copy that matters and losing it loses everything.
 *
 * Two decisions carry the whole thing:
 *
 * **It only runs against an empty database.** There is no merge here, and
 * pretending otherwise would be worse than not offering restore at all — on a
 * device already in use it would reinstate every transaction the user had
 * deleted, since a deletion is absence rather than a tombstone.
 *
 * **Server ids are preserved as Room row ids.** The server keys rows on
 * (uid, local_id), where local_id is the Room id they were pushed with. Letting
 * Room assign fresh ids on the way back in would make the next push look like a
 * different transaction, and the user's history would silently double.
 */
class RestoreFromServerUseCase @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val incomeDao: IncomeDao,
    private val expenseDataSource: MySqlExpenseDataSource,
    private val incomeDataSource: MySqlIncomeDataSource
) {
    suspend operator fun invoke(): RestoreOutcome {
        if (expenseDao.countExpenses() > 0 || incomeDao.countIncomes() > 0) {
            return RestoreOutcome.NotNeeded
        }

        return runCatching {
            val expenses = expenseDataSource.fetchAllExpenses().map { it.toRestoredEntity() }
            val incomes = incomeDataSource.fetchAllIncomes().map { it.toRestoredEntity() }

            // Re-checked after the fetch, which is slow enough for the user to
            // have added something meanwhile. Writing anyway would mix restored
            // rows into a database that is no longer empty, which is the exact
            // situation the guard above exists to avoid.
            if (expenseDao.countExpenses() > 0 || incomeDao.countIncomes() > 0) {
                return RestoreOutcome.NotNeeded
            }

            if (expenses.isNotEmpty()) expenseDao.insertExpenses(expenses)
            if (incomes.isNotEmpty()) incomeDao.insertIncomes(incomes)

            RestoreOutcome.Restored(expenses.size, incomes.size)
        }.getOrElse { error ->
            Log.w(TAG, "Restore from server failed.", error)
            RestoreOutcome.Failed
        }
    }

    private companion object {
        const val TAG = "RestoreFromServer"
    }
}

/**
 * Turns a stored expense back into a local row.
 *
 * Two invariants, both of which fail silently if broken:
 *
 * `id = localId` — the server keys rows on (uid, local_id), and local_id is the
 * Room id they were pushed with. A fresh id here would make the next push look
 * like a new transaction, quietly doubling the user's history server-side.
 *
 * `isSynced = true` — these rows came *from* the server. Left unsynced, the
 * next sweep would queue every one of them for upload and push the whole
 * restored history straight back to where it came from.
 */
internal fun ExpenseSyncPayload.toRestoredEntity() = ExpenseEntity(
    id = localId,
    title = title,
    amount = amount,
    category = category,
    date = date,
    isSynced = true,
    merchant = merchant,
    currency = currency.ifBlank { Currency.INR.code },
    source = source.ifBlank { ExpenseSource.MANUAL.name },
    reference = reference,
    dateIsAssumed = dateIsAssumed
)

/** See [toRestoredEntity] for the id and sync-flag reasoning. */
internal fun IncomeSyncPayload.toRestoredEntity() = IncomeEntity(
    id = localId,
    title = title,
    amount = amount,
    currency = currency.ifBlank { Currency.INR.code },
    source = source,
    note = note,
    date = date,
    isSynced = true,
    reference = reference,
    dateIsAssumed = dateIsAssumed
)

sealed class RestoreOutcome {
    data class Restored(val expenses: Int, val incomes: Int) : RestoreOutcome() {
        val total: Int get() = expenses + incomes
    }

    /** The device already holds data, so there was nothing to rebuild. */
    data object NotNeeded : RestoreOutcome()

    /** Left alone deliberately: a partial restore is worse than none. */
    data object Failed : RestoreOutcome()
}
