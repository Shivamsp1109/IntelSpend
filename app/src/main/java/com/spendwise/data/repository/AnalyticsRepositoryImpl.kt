package com.spendwise.data.repository

import com.spendwise.data.local.AnalyticsDao
import com.spendwise.data.local.BucketTotal
import com.spendwise.data.local.toDomain
import com.spendwise.domain.model.AnalyticsPeriod
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.DateRange
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.LargestExpense
import com.spendwise.domain.model.MerchantSpend
import com.spendwise.domain.model.SpendingSummary
import com.spendwise.domain.model.TimeBucket
import com.spendwise.domain.model.TimeBuckets
import com.spendwise.domain.repository.AnalyticsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class AnalyticsRepositoryImpl @Inject constructor(
    private val analyticsDao: AnalyticsDao
) : AnalyticsRepository {

    /**
     * The currency analytics are reported in.
     *
     * Adding ₹ and $ into one total would be silently wrong, and there is no
     * exchange-rate source in the app, so analytics reports on one currency and
     * says which. The most-used currency is the right default: it needs no
     * setting from the user and is correct for the overwhelmingly common case of
     * a single-currency history.
     */
    override suspend fun primaryCurrency(): Currency {
        val usage = analyticsDao.currencyUsage()
        val code = usage.firstOrNull()?.label ?: return Currency.INR
        return Currency.entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
            ?: Currency.INR
    }

    override suspend fun otherCurrenciesPresent(primary: Currency): List<Currency> =
        analyticsDao.currencyUsage()
            .mapNotNull { row ->
                Currency.entries.firstOrNull { it.code.equals(row.label, ignoreCase = true) }
            }
            .filter { it != primary }

    override suspend fun summary(period: AnalyticsPeriod, currency: Currency): SpendingSummary {
        val range = period.range()
        val previous = period.previous()
        val code = currency.code

        val expense = analyticsDao.totalExpense(range.start, range.end, code)
        val income = analyticsDao.totalIncome(range.start, range.end, code)
        val previousExpense = analyticsDao.totalExpense(previous.start, previous.end, code)
        val previousIncome = analyticsDao.totalIncome(previous.start, previous.end, code)
        val count = analyticsDao.expenseCount(range.start, range.end, code)

        return SpendingSummary(
            currency = currency,
            range = range,
            totalExpense = expense,
            totalIncome = income,
            previousExpense = previousExpense,
            previousIncome = previousIncome,
            transactionCount = count
        )
    }

    override suspend fun spendOverTime(
        period: AnalyticsPeriod,
        currency: Currency
    ): List<TimeBucket> {
        val range = period.range()
        val rows = if (period.bucketsByDay) {
            analyticsDao.expenseByDay(range.start, range.end, currency.code)
        } else {
            analyticsDao.expenseByMonth(range.start, range.end, currency.code)
        }
        return rows.filledOver(range, period.bucketsByDay)
    }

    /**
     * Bucketed the same way as [spendOverTime] so the two series can be drawn on
     * one axis. Bucketing income by month regardless of period would collapse a
     * month-long window into a single bar that no expense bar lines up with.
     */
    override suspend fun incomeOverTime(
        period: AnalyticsPeriod,
        currency: Currency
    ): List<TimeBucket> {
        val range = period.range()
        val rows = if (period.bucketsByDay) {
            analyticsDao.incomeByDay(range.start, range.end, currency.code)
        } else {
            analyticsDao.incomeByMonth(range.start, range.end, currency.code)
        }
        return rows.filledOver(range, period.bucketsByDay)
    }

    override suspend fun byCategory(
        period: AnalyticsPeriod,
        currency: Currency
    ): Map<ExpenseCategory, Double> {
        val range = period.range()
        return analyticsDao.expenseByCategory(range.start, range.end, currency.code)
            .associate { ExpenseCategory.fromLabel(it.label) to it.total }
    }

    override suspend fun categoryDeltas(
        period: AnalyticsPeriod,
        currency: Currency
    ): Map<ExpenseCategory, Double> {
        val previous = period.previous()
        return analyticsDao.expenseByCategory(previous.start, previous.end, currency.code)
            .associate { ExpenseCategory.fromLabel(it.label) to it.total }
    }

    override suspend fun topMerchants(
        period: AnalyticsPeriod,
        currency: Currency,
        limit: Int
    ): List<MerchantSpend> {
        val range = period.range()
        return analyticsDao.topMerchants(range.start, range.end, currency.code, limit)
            .map { MerchantSpend(name = it.label, total = it.total, transactionCount = it.count) }
    }

    override suspend fun weekdayTotals(
        period: AnalyticsPeriod,
        currency: Currency
    ): Map<Int, Double> {
        val range = period.range()
        return analyticsDao.expenseByWeekday(range.start, range.end, currency.code)
            .mapNotNull { row -> row.bucket.toIntOrNull()?.let { it to row.total } }
            .toMap()
    }

    override suspend fun largestExpenses(
        period: AnalyticsPeriod,
        currency: Currency,
        limit: Int
    ): List<LargestExpense> {
        val range = period.range()
        return analyticsDao.largestExpenses(range.start, range.end, currency.code, limit)
            .map {
                LargestExpense(
                    id = it.id,
                    title = it.title,
                    amount = it.amount,
                    category = ExpenseCategory.fromLabel(it.category),
                    date = it.date,
                    merchant = it.merchant
                )
            }
    }

    override suspend fun transactions(period: AnalyticsPeriod, currency: Currency): List<Expense> {
        val range = period.range()
        return analyticsDao.expensesInRange(range.start, range.end, currency.code)
            .map { it.toDomain() }
    }

    /**
     * SQL only returns buckets that contain rows, so a day with no spending is
     * simply absent. Charts need those gaps as zeroes or they compress the axis
     * and imply spending was continuous.
     */
    private fun List<BucketTotal>.filledOver(range: DateRange, byDay: Boolean): List<TimeBucket> =
        TimeBuckets.fill(
            range = range,
            byDay = byDay,
            totals = associate { it.bucket to it.total }
        )

    /**
     * One signal for any write to either table. The screen reloads its whole
     * snapshot on this rather than observing each aggregate separately, so the
     * figures on screen always come from a single consistent read.
     */
    override fun changes(): Flow<Unit> =
        combine(analyticsDao.expenseChanges(), analyticsDao.incomeChanges()) { _, _ -> Unit }
}
