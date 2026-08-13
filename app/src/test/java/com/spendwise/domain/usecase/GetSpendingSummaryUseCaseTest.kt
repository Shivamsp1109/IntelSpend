package com.spendwise.domain.usecase

import com.spendwise.domain.model.AnalyticsPeriod
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.DateRange
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.PeriodType
import com.spendwise.domain.repository.AnalyticsRepository
import com.spendwise.domain.repository.FakeAnalyticsRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Assembly of the one snapshot the whole analytics screen reads from. */
class GetSpendingSummaryUseCaseTest {

    private val period = AnalyticsPeriod(PeriodType.MONTH, LocalDate.of(2026, 8, 1))

    private fun useCaseWith(repository: AnalyticsRepository) =
        GetSpendingSummaryUseCase(repository, GetSmartInsightsUseCase())

    /**
     * A category you stopped spending on is a finding. Listing only this
     * period's categories would quietly turn a comparison into a list of what
     * you spent.
     */
    @Test
    fun `comparisons cover categories from either period`() = runTest {
        val useCase = useCaseWith(
            FakeAnalyticsRepository(
                current = mapOf(ExpenseCategory.FoodDining to 8_000.0),
                previous = mapOf(ExpenseCategory.Travel to 5_000.0)
            )
        )

        val comparisons = useCase(period).categoryComparisons

        assertEquals(
            setOf(ExpenseCategory.FoodDining, ExpenseCategory.Travel),
            comparisons.map { it.category }.toSet()
        )
        assertTrue(comparisons.single { it.category == ExpenseCategory.FoodDining }.isNew)
        assertTrue(comparisons.single { it.category == ExpenseCategory.Travel }.isDropped)
    }

    /**
     * The biggest category is usually the same one every month; the one that
     * moved is the one worth reading first.
     */
    @Test
    fun `comparisons rank by movement not by size`() = runTest {
        val useCase = useCaseWith(
            FakeAnalyticsRepository(
                current = mapOf(
                    ExpenseCategory.Utilities to 20_000.0,
                    ExpenseCategory.FoodDining to 6_000.0
                ),
                previous = mapOf(
                    ExpenseCategory.Utilities to 19_500.0,
                    ExpenseCategory.FoodDining to 1_000.0
                )
            )
        )

        val comparisons = useCase(period).categoryComparisons

        // Bills is four times larger but barely moved.
        assertEquals(ExpenseCategory.FoodDining, comparisons.first().category)
        assertEquals(5_000.0, comparisons.first().change, 0.0001)
    }

    @Test
    fun `categories absent from both periods are left out`() = runTest {
        val useCase = useCaseWith(
            FakeAnalyticsRepository(
                current = mapOf(ExpenseCategory.FoodDining to 100.0, ExpenseCategory.HealthMedical to 0.0),
                previous = mapOf(ExpenseCategory.HealthMedical to 0.0)
            )
        )

        val comparisons = useCase(period).categoryComparisons

        assertEquals(listOf(ExpenseCategory.FoodDining), comparisons.map { it.category })
    }

    @Test
    fun `weekday pattern is built over the period's own range`() = runTest {
        val useCase = useCaseWith(
            FakeAnalyticsRepository(weekday = mapOf(0 to 900.0))
        )

        val pattern = useCase(period).weekdayPattern

        assertEquals(7, pattern.days.size)
        // Every weekday turns up four or five times in a 31-day month.
        assertTrue(pattern.days.all { it.occurrences >= 4 })
        assertEquals(31, pattern.days.sumOf { it.occurrences })
    }

    @Test
    fun `an explicit currency overrides the primary one`() = runTest {
        val repository = FakeAnalyticsRepository(primary = Currency.INR, others = listOf(Currency.USD))
        val useCase = useCaseWith(repository)

        val snapshot = useCase(period, Currency.USD)

        assertEquals(Currency.USD, snapshot.currency)
        assertEquals(Currency.USD, repository.requestedCurrency)
    }

    @Test
    fun `the whole snapshot reports one currency and one range`() = runTest {
        val useCase = useCaseWith(
            FakeAnalyticsRepository(current = mapOf(ExpenseCategory.FoodDining to 500.0))
        )

        val snapshot = useCase(period)

        assertEquals(Currency.INR, snapshot.currency)
        assertEquals(period.range(), snapshot.summary.range)
        assertEquals(DateRange(period.range().start, period.range().end), snapshot.summary.range)
    }
}
