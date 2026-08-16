package com.spendwise.domain.repository

import com.spendwise.domain.model.AnalyticsPeriod
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.LargestExpense
import com.spendwise.domain.model.MerchantSpend
import com.spendwise.domain.model.SpendingSummary
import com.spendwise.domain.model.TimeBucket
import com.spendwise.domain.model.TransactionNature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A repository whose answers are set by the test rather than by a database.
 *
 * Shared so the use-case tests and the view-model tests exercise the same
 * contract; a second hand-written fake tends to drift from the interface and
 * quietly stop proving anything.
 */
class FakeAnalyticsRepository(
    private val current: Map<ExpenseCategory, Double> = emptyMap(),
    private val previous: Map<ExpenseCategory, Double> = emptyMap(),
    private val weekday: Map<Int, Double> = emptyMap(),
    private val merchants: List<MerchantSpend> = emptyList(),
    private val buckets: List<TimeBucket> = emptyList(),
    private val income: Double = 0.0,
    private val primary: Currency = Currency.INR,
    private val others: List<Currency> = emptyList(),
    private val rows: List<Expense> = emptyList(),
    private val byNature: Map<TransactionNature, Double> = emptyMap()
) : AnalyticsRepository {

    var requestedCurrency: Currency? = null
        private set

    override fun changes(): Flow<Unit> = flowOf(Unit)

    override suspend fun primaryCurrency() = primary

    override suspend fun otherCurrenciesPresent(primary: Currency) = others

    override suspend fun summary(period: AnalyticsPeriod, currency: Currency): SpendingSummary {
        requestedCurrency = currency
        return SpendingSummary(
            currency = currency,
            range = period.range(),
            totalExpense = current.values.sum(),
            totalIncome = income,
            previousExpense = previous.values.sum(),
            previousIncome = 0.0,
            transactionCount = current.size
        )
    }

    /**
     * Defaults to reporting the category total as spending, so a test that only
     * cares about categories still gets figures that add up rather than an
     * empty split that would make every health metric read as zero.
     */
    override suspend fun totalsByNature(period: AnalyticsPeriod, currency: Currency) =
        byNature.ifEmpty { mapOf(TransactionNature.Spending to current.values.sum()) }

    override suspend fun spendOverTime(period: AnalyticsPeriod, currency: Currency) = buckets

    override suspend fun incomeOverTime(period: AnalyticsPeriod, currency: Currency) =
        emptyList<TimeBucket>()

    override suspend fun byCategory(period: AnalyticsPeriod, currency: Currency) = current

    override suspend fun categoryDeltas(period: AnalyticsPeriod, currency: Currency) = previous

    override suspend fun topMerchants(period: AnalyticsPeriod, currency: Currency, limit: Int) =
        merchants.take(limit)

    override suspend fun weekdayTotals(period: AnalyticsPeriod, currency: Currency) = weekday

    override suspend fun largestExpenses(period: AnalyticsPeriod, currency: Currency, limit: Int) =
        emptyList<LargestExpense>()

    override suspend fun transactions(period: AnalyticsPeriod, currency: Currency) = rows
}
