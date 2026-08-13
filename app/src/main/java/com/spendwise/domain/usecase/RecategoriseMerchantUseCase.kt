package com.spendwise.domain.usecase

import android.util.Log
import com.spendwise.data.ingestion.category.LearnedCategoryStore
import com.spendwise.data.local.ExpenseDao
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.MerchantGroup
import com.spendwise.domain.model.TransactionNature
import com.spendwise.util.SyncScheduler
import javax.inject.Inject

/**
 * Re-files every transaction for one merchant, and remembers the decision.
 *
 * The remembering is the part that matters beyond the immediate fix. A user who
 * tells the app that "HDFC CC PAYMENT" is a credit-card payment has answered a
 * question the app would otherwise keep asking — of the model, at a cost, on
 * every future import. Writing it to the learned store puts that answer at the
 * front of the cascade for good.
 */
class RecategoriseMerchantUseCase @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val learnedCategoryStore: LearnedCategoryStore,
    private val syncScheduler: SyncScheduler
) {
    suspend operator fun invoke(
        group: MerchantGroup,
        category: ExpenseCategory,
        nature: TransactionNature
    ): Int {
        val updated = expenseDao.recategoriseMerchant(
            merchant = group.merchant,
            fromCategory = group.category.label,
            fromNature = group.nature.name,
            newCategory = category.label,
            newNature = nature.name
        )

        // Remembered even when nothing was updated: the user has still told us
        // what this merchant is, and that is worth keeping for the next import.
        runCatching { learnedCategoryStore.learnCategory(group.merchant, category.name) }
            .onFailure { Log.w(TAG, "Could not remember a category for ${group.merchant}.", it) }

        // The rows were marked unsynced by the update; nudge the sweep so the
        // correction reaches the server rather than waiting for the next import.
        if (updated > 0) {
            runCatching { syncScheduler.enqueueImmediateSync() }
                .onFailure { Log.w(TAG, "Could not schedule a sync after recategorising.", it) }
        }

        return updated
    }

    private companion object {
        const val TAG = "RecategoriseMerchant"
    }
}
