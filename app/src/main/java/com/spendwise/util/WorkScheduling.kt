package com.spendwise.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/**
 * Shared by [ExpenseSyncScheduler] and [SyncScheduler], which enqueue different
 * workers under different unique-work names but otherwise need identical
 * expedited, network-constrained one-shot requests.
 */
inline fun <reified W : ListenableWorker> enqueueExpeditedOneShot(
    context: Context,
    uniqueWorkName: String
) {
    val request = OneTimeWorkRequestBuilder<W>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
        uniqueWorkName,
        ExistingWorkPolicy.REPLACE,
        request
    )
}
