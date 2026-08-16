package com.spendwise.domain.usecase

import android.util.Log
import com.spendwise.data.local.DismissedRecurringCandidateEntity
import com.spendwise.data.local.RecurringEntryDao
import com.spendwise.data.remote.MySqlRecurringDataSource
import com.spendwise.domain.model.RecurringCandidate
import javax.inject.Inject

/**
 * Records that a detected pattern is not a commitment.
 *
 * What is stored is the pattern that was rejected — its cadence and the amount
 * it was charging — rather than the merchant alone. A blanket "never mention
 * this payee again" would be the easier thing to write and the wrong thing to
 * do: if that shop later starts taking the same amount every month, the user has
 * acquired a commitment they never agreed to be silent about.
 */
class DismissRecurringCandidateUseCase @Inject constructor(
    private val recurringEntryDao: RecurringEntryDao,
    private val remoteDataSource: MySqlRecurringDataSource
) {
    suspend operator fun invoke(candidate: RecurringCandidate, now: Long = System.currentTimeMillis()) {
        val dismissal = DismissedRecurringCandidateEntity(
            signature = candidate.signature,
            merchant = candidate.merchant,
            currency = candidate.currency.code,
            nature = candidate.nature.name,
            category = candidate.category.name,
            cadence = candidate.cadence.name,
            lastSeenAmount = candidate.amount,
            lastSeenOccurrenceDate = candidate.lastOccurrenceDate,
            dismissedAt = now
        )

        recurringEntryDao.insertDismissedCandidate(dismissal)

        // Best-effort, like every other immediate push in this app. The local
        // write is what makes the suggestion go away now; the server copy only
        // matters at restore time, and there is a whole reinstall between here
        // and needing it.
        runCatching { remoteDataSource.upsertDismissal(dismissal) }
            .onFailure { Log.w(TAG, "Could not sync a dismissal; it stays local.", it) }
    }

    private companion object {
        const val TAG = "DismissRecurring"
    }
}
