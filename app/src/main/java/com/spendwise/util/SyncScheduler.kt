package com.spendwise.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the unified [SyncWorker] for an expedited one-shot run.
 *
 * Call this from any write path that isn't already handled by the entity's
 * own repository (Income/Goal/Recurring now each call the remote API
 * directly on write; this scheduler is the fallback for when those
 * immediate attempts fail and the data needs a background retry sweep).
 *
 * The periodic 6-hour job is registered in [NotificationScheduler].
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun enqueueImmediateSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "spendwise_immediate_sync",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
