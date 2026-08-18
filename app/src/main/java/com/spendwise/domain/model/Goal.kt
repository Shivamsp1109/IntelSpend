package com.spendwise.domain.model

/**
 * Domain model for a savings goal.
 *
 * @param type          The goal category (e.g. emergency fund, vacation).
 * @param targetAmount  Total savings target in the user's default currency.
 * @param targetDate    Epoch-millis deadline for reaching the goal.
 * @param currentSaved  Amount already saved towards this goal.
 * @param monthlyContribution Suggested / committed monthly top-up amount.
 */
data class Goal(
    val id: Int = 0,
    val type: GoalType,
    val targetAmount: Double,
    val targetDate: Long,
    val currentSaved: Double = 0.0,
    /**
     * The figure the user committed to putting aside each month.
     *
     * Deliberately the only contribution field. What a goal *would* need is
     * arithmetic the engine does on demand and reports beside this; it is never
     * written back here. A suggestion quietly promoted to a commitment is the
     * app deciding something on the user's behalf.
     */
    val monthlyContribution: Double = 0.0,
    val currency: Currency = Currency.INR,
    /** Whether [targetAmount] is today's price or a figure already in future money. */
    val amountBasis: GoalAmountBasis = GoalAmountBasis.TODAYS_MONEY,
    val priority: GoalPriority = GoalPriority.IMPORTANT,
    val flexibility: GoalFlexibility = GoalFlexibility.BOTH_FLEXIBLE,
    val lifecycle: GoalStatusLifecycle = GoalStatusLifecycle.ACTIVE,
    /** Where the money is expected to come from, in the user's own words. */
    val fundingSource: String? = null,
    val isSynced: Boolean = false
) {
    /** Progress as a fraction in [0, 1]. */
    val progressFraction: Double
        get() = if (targetAmount > 0) (currentSaved / targetAmount).coerceIn(0.0, 1.0) else 0.0
}
