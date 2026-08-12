package com.spendwise.domain.usecase

import com.spendwise.domain.model.AnalyticsPeriod
import com.spendwise.domain.model.CategoryComparison
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.Insight
import com.spendwise.domain.model.LargestExpense
import com.spendwise.domain.model.MerchantSpend
import com.spendwise.domain.model.SpendingSummary
import com.spendwise.domain.model.TimeBucket
import com.spendwise.domain.model.WeekdayPattern
import com.spendwise.domain.repository.AnalyticsRepository
import javax.inject.Inject
import kotlin.math.abs

/**
 * Everything the analytics screen needs for one period, resolved together.
 *
 * Gathering it in a single call keeps the whole screen on one currency and one
 * date range. When each chart fetched its own slice they silently disagreed —
 * the monthly bars covered six months while the category split covered all
 * time, and nothing on screen said so.
 */
class GetSpendingSummaryUseCase @Inject constructor(
    private val analyticsRepository: AnalyticsRepository,
    private val getSmartInsightsUseCase: GetSmartInsightsUseCase
) {
    suspend operator fun invoke(
        period: AnalyticsPeriod,
        currency: Currency? = null
    ): AnalyticsSnapshot {
        val resolved = currency ?: analyticsRepository.primaryCurrency()

        val previousByCategory = analyticsRepository.categoryDeltas(period, resolved)
        val currentByCategory = analyticsRepository.byCategory(period, resolved)
        val weekdayTotals = analyticsRepository.weekdayTotals(period, resolved)

        val snapshot = AnalyticsSnapshot(
            period = period,
            currency = resolved,
            excludedCurrencies = analyticsRepository.otherCurrenciesPresent(resolved),
            summary = analyticsRepository.summary(period, resolved),
            spendOverTime = analyticsRepository.spendOverTime(period, resolved),
            incomeOverTime = analyticsRepository.incomeOverTime(period, resolved),
            byCategory = currentByCategory,
            categoryChange = currentByCategory.mapValues { (category, total) ->
                total - (previousByCategory[category] ?: 0.0)
            },
            categoryComparisons = comparisons(currentByCategory, previousByCategory),
            topMerchants = analyticsRepository.topMerchants(period, resolved),
            weekdayTotals = weekdayTotals,
            weekdayPattern = WeekdayPattern.from(weekdayTotals, period.range()),
            largestExpenses = analyticsRepository.largestExpenses(period, resolved)
        )

        // Insights read the finished snapshot, so every rule sees the same
        // figures the charts do rather than querying for its own.
        return snapshot.copy(insights = getSmartInsightsUseCase(snapshot))
    }

    /**
     * Ranked by how far each category moved, not by how much it holds. The
     * largest category is usually the same one every month; the one that moved
     * is the one worth reading first.
     */
    private fun comparisons(
        current: Map<ExpenseCategory, Double>,
        previous: Map<ExpenseCategory, Double>
    ): List<CategoryComparison> =
        (current.keys + previous.keys)
            .map { category ->
                CategoryComparison(
                    category = category,
                    current = current[category] ?: 0.0,
                    previous = previous[category] ?: 0.0
                )
            }
            .filter { it.current > 0.0 || it.previous > 0.0 }
            .sortedByDescending { abs(it.change) }
}

data class AnalyticsSnapshot(
    val period: AnalyticsPeriod,
    val currency: Currency,
    /**
     * Currencies present in the data but not included in these figures, so the
     * screen can say what it is leaving out instead of quietly under-reporting.
     */
    val excludedCurrencies: List<Currency> = emptyList(),
    val summary: SpendingSummary,
    val spendOverTime: List<TimeBucket> = emptyList(),
    val incomeOverTime: List<TimeBucket> = emptyList(),
    val byCategory: Map<ExpenseCategory, Double> = emptyMap(),
    /** Movement vs. the previous period; positive means spending rose. */
    val categoryChange: Map<ExpenseCategory, Double> = emptyMap(),
    /** The same movement in full, including categories that dropped to nothing. */
    val categoryComparisons: List<CategoryComparison> = emptyList(),
    val topMerchants: List<MerchantSpend> = emptyList(),
    val weekdayTotals: Map<Int, Double> = emptyMap(),
    /** Weekday totals averaged per occurrence, so the days compare fairly. */
    val weekdayPattern: WeekdayPattern = WeekdayPattern(emptyList()),
    val largestExpenses: List<LargestExpense> = emptyList(),
    /** Ranked findings derived from the figures above. */
    val insights: List<Insight> = emptyList()
)
