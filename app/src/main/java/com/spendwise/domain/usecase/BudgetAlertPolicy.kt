package com.spendwise.domain.usecase

import com.spendwise.data.local.CategoryBudgetEntity
import com.spendwise.domain.model.CategoryBudgetStatus

/**
 * Decides which budgets are worth interrupting someone about.
 *
 * A budget alert is only useful before the money is gone, and only if it is rare
 * enough to still register. Firing on every scan would make it wallpaper; firing
 * only at the end of the month would make it a post-mortem. So each budget gets
 * at most two mentions a month — one approaching the limit, one on passing it —
 * and both stay silent thereafter until the month turns over.
 */
object BudgetAlertPolicy {

    /** Announced on the way past, in ascending order. */
    private val THRESHOLDS = listOf(80, 100)

    /**
     * Budgets to alert on now, each with the threshold it has reached.
     *
     * @param month the current `yyyy-MM`, against which a stored alert is judged
     *   stale — a new month starts every budget's alerting afresh, however few
     *   days have passed.
     */
    fun alerts(
        budgets: List<CategoryBudgetEntity>,
        statuses: List<CategoryBudgetStatus>,
        month: String
    ): List<BudgetAlert> {
        val statusBy = statuses.associateBy { it.category.label to it.currency.code }

        return budgets.mapNotNull { budget ->
            val status = statusBy[budget.category to budget.currency] ?: return@mapNotNull null

            val reached = THRESHOLDS.filter { status.percentUsed >= it }.maxOrNull()
                ?: return@mapNotNull null

            // A stored threshold from a previous month says nothing about this
            // one. Without the month check, someone who hit 100% in July would
            // never be told again for the rest of the year.
            val alreadySaid =
                if (budget.lastAlertedMonth == month) budget.lastAlertedThreshold else 0

            // Strictly greater, so passing 100% is still worth saying even
            // though 80% was already announced — and so neither is repeated.
            if (reached <= alreadySaid) return@mapNotNull null

            BudgetAlert(budgetId = budget.id, status = status, threshold = reached)
        }
    }
}

data class BudgetAlert(
    val budgetId: Int,
    val status: CategoryBudgetStatus,
    /** 80 or 100 — what this alert is announcing. */
    val threshold: Int
)
