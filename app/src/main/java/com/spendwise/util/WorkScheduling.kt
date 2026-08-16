package com.spendwise.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Shared by [ExpenseSyncScheduler] and [SyncScheduler], which enqueue different
 * workers under different unique-work names but otherwise need identical
 * network-constrained one-shot requests.
 *
 * Two things here were causing sync to stall repeatedly during a burst of
 * writes, on a connection that never dropped.
 *
 * **KEEP, not REPLACE.** Every saved expense asks for a sync. With REPLACE, the
 * tenth save cancelled the run started by the ninth — mid-upload — and queued a
 * fresh one, which the eleventh then cancelled in turn. Adding a batch of
 * transactions could therefore cancel its own sync over and over and finish
 * having uploaded almost nothing. KEEP lets the run in progress finish; the
 * worker drains everything pending, so a request arriving while it runs is not
 * information it lacks.
 *
 * **Not expedited.** Expedited work draws on a per-app quota that a burst
 * exhausts in seconds. Past that the fallback policy demotes each request to
 * ordinary deferrable work, which the system then schedules whenever it feels
 * like it — the pauses of a minute or more, followed by a spontaneous restart,
 * that made this look like a connectivity problem. A plain one-shot with its
 * constraint already met runs promptly and keeps running promptly.
 */
inline fun <reified W : ListenableWorker> enqueueSyncOneShot(
    context: Context,
    uniqueWorkName: String
) {
    val request = OneTimeWorkRequestBuilder<W>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
        uniqueWorkName,
        ExistingWorkPolicy.KEEP,
        request
    )
}
