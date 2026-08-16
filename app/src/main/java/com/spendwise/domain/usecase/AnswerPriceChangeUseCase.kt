package com.spendwise.domain.usecase

import android.util.Log
import com.spendwise.data.local.RecurringEntryDao
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.util.SyncScheduler
import javax.inject.Inject

/**
 * Applies or refuses a price change the app has spotted.
 *
 * Both answers are recorded. Accepting makes the new figure the agreed one;
 * refusing keeps the old figure *and remembers the refusal*, because the charge
 * that prompted the question will still be sitting in the history next month and
 * would otherwise raise it again, and again.
 */
class AnswerPriceChangeUseCase @Inject constructor(
    private val recurringEntryDao: RecurringEntryDao,
    private val syncScheduler: SyncScheduler
) {
    suspend fun accept(entry: RecurringEntry) {
        val newAmount = entry.pendingAmount ?: return
        recurringEntryDao.applyPendingAmount(entry.id, newAmount)

        runCatching { syncScheduler.enqueueImmediateSync() }
            .onFailure { Log.w(TAG, "Could not schedule a sync after a price change.", it) }
    }

    suspend fun keepExisting(entry: RecurringEntry) {
        val refused = entry.pendingAmount ?: return
        recurringEntryDao.declinePendingAmount(entry.id, refused)
    }

    private companion object {
        const val TAG = "AnswerPriceChange"
    }
}
