package com.spendwise.domain.usecase

import android.util.Log
import com.spendwise.data.local.RecurringEntryDao
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.model.RecurringStatus
import com.spendwise.util.SyncScheduler
import javax.inject.Inject

/**
 * Pauses, resumes or ends a commitment.
 *
 * Kept as a status change rather than a delete because the history is worth
 * keeping either way: what a paused membership costs is the reason it can be
 * resumed without asking, and a loan that has been paid off still explains where
 * last year's money went. Deleting would also let detection re-suggest the thing
 * the user just dismissed from their list.
 */
class UpdateRecurringStatusUseCase @Inject constructor(
    private val recurringEntryDao: RecurringEntryDao,
    private val syncScheduler: SyncScheduler
) {
    suspend operator fun invoke(entry: RecurringEntry, status: RecurringStatus) {
        recurringEntryDao.updateStatus(entry.id, status.name)

        runCatching { syncScheduler.enqueueImmediateSync() }
            .onFailure { Log.w(TAG, "Could not schedule a sync after a status change.", it) }
    }

    private companion object {
        const val TAG = "UpdateRecurringStatus"
    }
}
