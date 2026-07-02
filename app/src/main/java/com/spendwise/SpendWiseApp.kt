package com.spendwise

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.spendwise.util.ExpenseSyncScheduler
import com.spendwise.util.NotificationScheduler
import com.spendwise.util.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SpendWiseApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notificationScheduler: NotificationScheduler
    /** Legacy fast-path for expense-only immediate sync (kept for ExpenseRepositoryImpl). */
    @Inject lateinit var expenseSyncScheduler: ExpenseSyncScheduler
    /** Unified catch-all sweep covering all 4 entity types. */
    @Inject lateinit var syncScheduler: SyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        notificationScheduler.scheduleDailyReminder()  // also registers periodic SyncWorker
        expenseSyncScheduler.enqueueImmediateSync()    // expense fast-path on boot
        syncScheduler.enqueueImmediateSync()           // full sweep on boot (catches income/goal/recurring)
    }
}

