package com.spendwise.domain.model

/**
 * Every transaction for one merchant that currently shares a category and a
 * nature — the unit a bulk correction operates on.
 *
 * Merchants rather than rows, because that is how categorisation goes wrong. If
 * "HDFC CC PAYMENT" was filed as spending, all forty of its rows were, and
 * fixing them one at a time is work the app should be doing.
 */
data class MerchantGroup(
    val merchant: String,
    val category: ExpenseCategory,
    val nature: TransactionNature,
    val count: Int,
    val total: Double,
    val latestDate: Long,
    val currency: Currency
) {
    /** Needs attention: uncategorised, and counted in the user's spending. */
    val isUncategorised: Boolean
        get() = category == ExpenseCategory.Other && nature.isSpending

    /**
     * How much fixing this one merchant is worth.
     *
     * The amount, because that is what a wrong category actually distorts —
     * eighty ₹40 fares matter less to the shape of a month than one ₹80,000
     * transfer sitting in the spending total. Uncategorised groups are lifted
     * above the rest so the work with a visible payoff comes first.
     */
    val impact: Double
        get() = if (isUncategorised) total * UNCATEGORISED_WEIGHT else total

    private companion object {
        /**
         * Enough to float uncategorised groups above larger settled ones without
         * burying a genuinely huge misfiled transfer beneath a small unknown.
         */
        const val UNCATEGORISED_WEIGHT = 3.0
    }
}
