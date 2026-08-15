package com.spendwise.domain.model

/**
 * A standing monthly ceiling for one category.
 *
 * Distinct from [BudgetStatus], which compares a whole month's spending against
 * income on the home screen. That answers "am I living within my means"; this
 * answers "am I spending more on eating out than I meant to", which is the
 * question a person can actually act on.
 */
data class CategoryBudget(
    val id: Int = 0,
    val category: ExpenseCategory,
    val monthlyLimit: Double,
    val currency: Currency = Currency.INR
)

/**
 * A category budget measured against what has actually been spent this month.
 *
 * Only spending counts towards it. A transfer between the user's own accounts or
 * a credit-card bill payment is money moving, not money gone, and letting either
 * eat into a category budget would report someone as overspent for shifting
 * their own savings around.
 */
data class CategoryBudgetStatus(
    val budget: CategoryBudget,
    val spent: Double
) {
    val category: ExpenseCategory get() = budget.category
    val currency: Currency get() = budget.currency
    val limit: Double get() = budget.monthlyLimit

    /**
     * How much of the limit is used, as a fraction. Not capped at 1.0 — going
     * over is the thing worth knowing, and clamping would hide by how much.
     */
    val fractionUsed: Double
        get() = if (limit > 0) spent / limit else 0.0

    val percentUsed: Int get() = (fractionUsed * 100).toInt()

    /** Negative once the budget is blown, which is what the screen wants to say. */
    val remaining: Double get() = limit - spent

    val isOverBudget: Boolean get() = spent > limit

    /** Close enough that mentioning it is still useful rather than merely late. */
    val isNearLimit: Boolean get() = !isOverBudget && fractionUsed >= NEAR_LIMIT_FRACTION

    companion object {
        const val NEAR_LIMIT_FRACTION = 0.8
    }
}
