package com.spendwise.domain.repository

import com.spendwise.domain.model.AnalyticsPeriod
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.LargestExpense
import com.spendwise.domain.model.MerchantSpend
import com.spendwise.domain.model.SpendingSummary
import com.spendwise.domain.model.TimeBucket
import kotlinx.coroutines.flow.Flow

/**
 * Aggregated reads for the analytics screens.
 *
 * Every method takes a currency because totals across denominations are
 * meaningless without an exchange rate, and the app has no rate source. Callers
 * resolve it once via [primaryCurrency] and pass it down.
 */
interface AnalyticsRepository {

    /**
     * Emits whenever expenses or incomes change, so a screen holding a snapshot
     * knows to take a fresh one. The reads below are deliberately one-shot; this
     * is the single trigger that drives them.
     */
    fun changes(): Flow<Unit>

    /** Most-used currency in the expense history; INR when there is no history. */
    suspend fun primaryCurrency(): Currency

    /** Other currencies present, so the UI can say what is being excluded. */
    suspend fun otherCurrenciesPresent(primary: Currency): List<Currency>

    suspend fun summary(period: AnalyticsPeriod, currency: Currency): SpendingSummary

    suspend fun spendOverTime(period: AnalyticsPeriod, currency: Currency): List<TimeBucket>

    suspend fun incomeOverTime(period: AnalyticsPeriod, currency: Currency): List<TimeBucket>

    suspend fun byCategory(period: AnalyticsPeriod, currency: Currency): Map<ExpenseCategory, Double>

    /** Same shape as [byCategory] but for the previous period, for deltas. */
    suspend fun categoryDeltas(
        period: AnalyticsPeriod,
        currency: Currency
    ): Map<ExpenseCategory, Double>

    suspend fun topMerchants(
        period: AnalyticsPeriod,
        currency: Currency,
        limit: Int = 10
    ): List<MerchantSpend>

    /** Keyed 0 (Sunday) through 6 (Saturday), matching SQLite's %w. */
    suspend fun weekdayTotals(period: AnalyticsPeriod, currency: Currency): Map<Int, Double>

    suspend fun largestExpenses(
        period: AnalyticsPeriod,
        currency: Currency,
        limit: Int = 5
    ): List<LargestExpense>

    /**
     * The individual transactions behind the aggregates, for export. Scoped the
     * same way, so a report cannot disagree with the screen that produced it.
     */
    suspend fun transactions(period: AnalyticsPeriod, currency: Currency): List<Expense>
}
