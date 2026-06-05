package com.spendwise.domain.model

data class BudgetStatus(
    val monthlyBudget: Double,
    val spentThisMonth: Double
) {
    val usagePercent: Double =
        if (monthlyBudget <= 0.0) 0.0 else spentThisMonth / monthlyBudget

    val isNearLimit: Boolean = usagePercent >= 0.9
    val isExceeded: Boolean = usagePercent >= 1.0
}
