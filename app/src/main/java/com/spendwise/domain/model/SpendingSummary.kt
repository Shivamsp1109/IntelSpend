package com.spendwise.domain.model

/** One bucket of a time series — a day ('2026-08-11') or a month ('2026-08'). */
data class TimeBucket(
    val key: String,
    val total: Double
)

data class MerchantSpend(
    val name: String,
    val total: Double,
    val transactionCount: Int
) {
    /**
     * Small-and-frequent spending hides inside a category total — forty ₹200
     * orders look like one ₹8,000 line. The average makes that visible.
     */
    val averageTransaction: Double
        get() = if (transactionCount == 0) 0.0 else total / transactionCount
}

/**
 * A category's spending this period set against the last one.
 *
 * Covers categories present in *either* period, because a category you stopped
 * spending on is a finding in its own right — dropping it would quietly turn a
 * comparison into a list of what you spent this month.
 */
data class CategoryComparison(
    val category: ExpenseCategory,
    val current: Double,
    val previous: Double
) {
    val change: Double get() = current - previous

    /** Null when there is no previous spending to be a percentage of. */
    val changePercent: Double?
        get() = if (previous <= 0.0) null else change / previous

    val isNew: Boolean get() = previous <= 0.0 && current > 0.0

    val isDropped: Boolean get() = current <= 0.0 && previous > 0.0
}

data class LargestExpense(
    val id: Int,
    val title: String,
    val amount: Double,
    val category: ExpenseCategory,
    val date: Long,
    val merchant: String?
)

/**
 * Headline figures for a period, carrying the previous period alongside so the
 * UI can show movement rather than a bare number. A total on its own says
 * nothing — whether ₹40,000 is good or bad depends entirely on last month.
 */
data class SpendingSummary(
    val currency: Currency,
    val range: DateRange,
    val totalExpense: Double,
    val totalIncome: Double,
    val previousExpense: Double,
    val previousIncome: Double,
    val transactionCount: Int
) {
    val net: Double get() = totalIncome - totalExpense

    /**
     * Share of income kept. Null when there is no income recorded for the
     * period — zero would read as "saved nothing", which is a different and
     * misleading claim.
     */
    val savingsRate: Double?
        get() = if (totalIncome <= 0.0) null else (totalIncome - totalExpense) / totalIncome

    val expenseChange: Double get() = totalExpense - previousExpense
    val incomeChange: Double get() = totalIncome - previousIncome

    /** Fractional change vs. the previous period; null when there is no base to compare against. */
    val expenseChangePercent: Double?
        get() = if (previousExpense <= 0.0) null else expenseChange / previousExpense

    val averagePerDay: Double
        get() = if (range.dayCount == 0) 0.0 else totalExpense / range.dayCount

    val averagePerTransaction: Double
        get() = if (transactionCount == 0) 0.0 else totalExpense / transactionCount
}
