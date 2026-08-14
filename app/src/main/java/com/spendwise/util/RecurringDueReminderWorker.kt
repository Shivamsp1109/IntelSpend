package com.spendwise.util

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spendwise.data.local.RecurringEntryDao
import com.spendwise.data.local.toDomain
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.usecase.ReconcileRecurringPaymentsUseCase
import com.spendwise.domain.usecase.RecurringReminderPolicy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Tells the user about payments falling due in the next few days.
 *
 * Runs daily. The ordering inside it is the entire point: reconciliation happens
 * first, so any payment that has already gone out has moved its commitment's due
 * date forward and the commitment simply is not in the window any more. Without
 * that step the app would announce rent as due to someone who paid it a week
 * ago — and one reminder like that is enough for the feature to be turned off,
 * after which the useful ones never arrive either.
 */
@HiltWorker
class RecurringDueReminderWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val recurringEntryDao: RecurringEntryDao,
    private val reconcileRecurringPayments: ReconcileRecurringPaymentsUseCase,
    private val reminderState: RecurringReminderState
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        RecurringNotifications.ensureChannels(context)
        if (!RecurringNotifications.canNotify(context)) return Result.success()

        // Best-effort: a reconciliation that fails leaves due dates one pass
        // stale, which is worth far less than skipping the reminder entirely.
        runCatching { reconcileRecurringPayments() }
            .onFailure { Log.w(TAG, "Could not reconcile before reminding.", it) }

        val now = System.currentTimeMillis()
        val window = now + TimeUnit.DAYS.toMillis(RecurringReminderPolicy.LEAD_DAYS)

        val entries = runCatching {
            recurringEntryDao.getDueBetween(from = 0, until = window).map { it.toDomain() }
        }.getOrElse {
            Log.w(TAG, "Could not read upcoming payments.", it)
            return Result.retry()
        }

        val due = RecurringReminderPolicy.due(
            entries = entries,
            lastRemindedDueDate = reminderState::lastRemindedDueDate,
            now = now
        )
        if (due.isEmpty()) return Result.success()

        RecurringNotifications.show(
            context = context,
            id = RecurringNotifications.DUE_NOTIFICATION_ID,
            notification = RecurringNotifications.build(
                context = context,
                channelId = RecurringNotifications.DUE_CHANNEL_ID,
                title = title(due),
                text = body(due)
            )
        )

        // Recorded only after the notification has gone out, so a crash in
        // between means the user is told twice rather than never.
        due.forEach { entry ->
            entry.nextDueDate?.let { reminderState.markReminded(entry.id, it) }
        }

        return Result.success()
    }

    private fun title(due: List<RecurringEntry>): String =
        if (due.size == 1) "${due.first().title} is due soon"
        else "${due.size} payments due soon"

    /**
     * One line per payment, with the amount and the day.
     *
     * The amount is the point. "Rent is due" is a fact the user already knows;
     * "₹22,000 rent due Friday" is something they can act on without opening
     * anything.
     */
    private fun body(due: List<RecurringEntry>): String = due.joinToString("\n") { entry ->
        val amount = CurrencyFormatter.format(entry.amount, entry.currency)
        val on = entry.nextDueDate?.let { DateUtils.formatDate(it) }
        if (on != null) "$amount · ${entry.title} · $on" else "$amount · ${entry.title}"
    }

    private companion object {
        const val TAG = "RecurringDueReminder"
    }
}
