package com.spendwise.util

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spendwise.data.local.CategoryBudgetDao
import com.spendwise.data.local.toDomain
import com.spendwise.domain.model.BudgetMonth
import com.spendwise.domain.usecase.BudgetAlert
import com.spendwise.domain.usecase.BudgetAlertPolicy
import com.spendwise.domain.usecase.GetCategoryBudgetStatusUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Warns when a category budget is running out.
 *
 * Daily. A budget alert is only useful before the money is gone, and only if it
 * is rare enough to still register — so each budget gets at most two mentions a
 * month, one approaching the limit and one on passing it, and stays quiet after
 * that until the month turns over.
 *
 * Deliberately reads the database directly rather than observing: a worker wants
 * one answer and then to finish, and a Flow here would keep the process alive
 * waiting for changes nobody is watching for.
 */
@HiltWorker
class BudgetAlertWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val budgetDao: CategoryBudgetDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        RecurringNotifications.ensureChannels(context)
        if (!RecurringNotifications.canNotify(context)) return Result.success()

        val month = BudgetMonth.containing()

        val alerts = runCatching {
            val budgets = budgetDao.getBudgets()
            if (budgets.isEmpty()) return Result.success()

            val statuses = GetCategoryBudgetStatusUseCase.statusesFor(
                budgets = budgets.map { it.toDomain() },
                spend = budgetDao.getCategorySpend(month.start, month.endInclusive)
            )
            BudgetAlertPolicy.alerts(budgets, statuses, month.key)
        }.getOrElse {
            Log.w(TAG, "Could not check budgets.", it)
            return Result.retry()
        }

        if (alerts.isEmpty()) return Result.success()

        RecurringNotifications.show(
            context = context,
            id = NOTIFICATION_ID,
            notification = RecurringNotifications.build(
                context = context,
                channelId = RecurringNotifications.BUDGET_CHANNEL_ID,
                title = title(alerts),
                text = body(alerts)
            )
        )

        // Recorded only after the notification has gone out, so a crash in
        // between means the user is told twice rather than never.
        for (alert in alerts) {
            runCatching { budgetDao.markAlerted(alert.budgetId, alert.threshold, month.key) }
                .onFailure { Log.w(TAG, "Could not record a budget alert.", it) }
        }

        return Result.success()
    }

    private fun title(alerts: List<BudgetAlert>): String {
        val over = alerts.count { it.threshold >= 100 }
        return when {
            alerts.size == 1 && over == 1 -> "${alerts.first().status.category.label} is over budget"
            alerts.size == 1 -> "${alerts.first().status.category.label} is nearly spent"
            over > 0 -> "$over of your budgets are spent"
            else -> "${alerts.size} budgets are nearly spent"
        }
    }

    /**
     * One line per budget, with what is left rather than what has gone.
     *
     * "₹1,200 left of ₹6,000" is something to act on. "You have spent ₹4,800" is
     * a fact the user has to do arithmetic on before it means anything.
     */
    private fun body(alerts: List<BudgetAlert>): String = alerts.joinToString("\n") { alert ->
        val status = alert.status
        val label = status.category.label
        if (status.isOverBudget) {
            "$label · ${CurrencyFormatter.format(-status.remaining, status.currency)} over"
        } else {
            "$label · ${CurrencyFormatter.format(status.remaining, status.currency)} left"
        }
    }

    private companion object {
        const val TAG = "BudgetAlertWorker"
        const val NOTIFICATION_ID = 2003
    }
}
