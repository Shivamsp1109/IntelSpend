package com.spendwise.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun scheduleDailyReminder() {
        val delay = initialDelayUntilEvening()
        val request = PeriodicWorkRequestBuilder<DailyReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "daily_expense_reminder",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )

        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "spendwise_periodic_sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest
        )

        scheduleRecurringWork()
    }

    /**
     * The two recurring-payment jobs.
     *
     * Neither needs the network: detection reads local history, and the reminder
     * reconciles against rows already on the device. Requiring a connection would
     * mean someone offline stops being told about their rent.
     */
    fun scheduleRecurringWork() {
        // Morning, so a payment due in three days is mentioned at a point in the
        // day when something can still be done about it.
        val dueReminders = PeriodicWorkRequestBuilder<RecurringDueReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayUntilHour(9), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "spendwise_recurring_due_reminder",
            ExistingPeriodicWorkPolicy.UPDATE,
            dueReminders
        )

        // Weekly: a pattern that took months to form does not become newsworthy
        // overnight, and a daily "we found something" is how a useful suggestion
        // turns into noise the user mutes.
        val detection = PeriodicWorkRequestBuilder<RecurringDetectionWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(initialDelayUntilHour(11), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "spendwise_recurring_detection",
            ExistingPeriodicWorkPolicy.UPDATE,
            detection
        )

        // Evening, when the day's spending has mostly happened. A budget warning
        // at breakfast is based on yesterday's figures.
        val budgets = PeriodicWorkRequestBuilder<BudgetAlertWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayUntilHour(20), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "spendwise_budget_alerts",
            ExistingPeriodicWorkPolicy.UPDATE,
            budgets
        )
    }

    private fun initialDelayUntilEvening(): Long = initialDelayUntilHour(19)

    /** Milliseconds until the next occurrence of [hour] o'clock, local time. */
    private fun initialDelayUntilHour(hour: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }
}
