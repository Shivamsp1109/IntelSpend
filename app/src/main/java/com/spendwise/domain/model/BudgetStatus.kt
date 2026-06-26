package com.spendwise.domain.model

import kotlin.math.roundToInt

data class BudgetStatus(
    val monthlyBudget: Double,
    val spentThisMonth: Double
) {
    val usagePercent: Double =
        if (monthlyBudget <= 0.0) 0.0 else spentThisMonth / monthlyBudget

    val roundedUsagePercent: Int = (usagePercent * 100).roundToInt()
    val isNearLimit: Boolean = usagePercent >= 0.7
    val isExceeded: Boolean = usagePercent >= 1.0
}
