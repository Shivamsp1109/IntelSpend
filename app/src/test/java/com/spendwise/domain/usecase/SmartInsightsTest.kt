package com.spendwise.domain.usecase

import com.spendwise.domain.model.AnalyticsPeriod
import com.spendwise.domain.model.CategoryComparison
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.InsightTone
import com.spendwise.domain.model.MerchantSpend
import com.spendwise.domain.model.PeriodType
import com.spendwise.domain.model.SpendingSummary
import com.spendwise.domain.model.TimeBucket
import com.spendwise.domain.model.TimeBuckets
import com.spendwise.domain.model.WeekdayPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The insight rules.
 *
 * Each rule has a threshold, so the tests come in pairs: one where the rule
 * should fire and one just below the line where it must stay quiet. A findings
 * list that always finds something is noise, and the reader stops reading it.
 */
class SmartInsightsTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val useCase = GetSmartInsightsUseCase()
    private val period = AnalyticsPeriod(PeriodType.MONTH, LocalDate.of(2026, 8, 1))

    private fun snapshot(
        expense: Double = 0.0,
        income: Double = 0.0,
        previousExpense: Double = 0.0,
        count: Int = 0,
        comparisons: List<CategoryComparison> = emptyList(),
        merchants: List<MerchantSpend> = emptyList(),
        days: Map<String, Double> = emptyMap(),
        weekday: Map<Int, Double> = emptyMap()
    ): AnalyticsSnapshot {
        val range = period.range(zone)
        return AnalyticsSnapshot(
            period = period,
            currency = Currency.INR,
            summary = SpendingSummary(
                currency = Currency.INR,
                range = range,
                totalExpense = expense,
                totalIncome = income,
                previousExpense = previousExpense,
                previousIncome = 0.0,
                transactionCount = count
            ),
            spendOverTime = TimeBuckets.fill(range, byDay = true, totals = days, zone = zone),
            categoryComparisons = comparisons,
            topMerchants = merchants,
            weekdayPattern = WeekdayPattern.from(weekday, range, zone)
        )
    }

    /** Mid-period, so the pace rule can fire; 11 Aug is the 11th of 31 days. */
    private val midPeriod = LocalDate.of(2026, 8, 11)
        .atStartOfDay(zone).toInstant().toEpochMilli()

    /** After the period ends, so only the retrospective rules apply. */
    private val afterPeriod = LocalDate.of(2026, 9, 5)
        .atStartOfDay(zone).toInstant().toEpochMilli()

    // ── Nothing to say ────────────────────────────────────────────────────────

    @Test
    fun `an empty period produces no insights`() {
        assertTrue(useCase(snapshot(), afterPeriod).isEmpty())
    }

    @Test
    fun `a period that barely moved produces nothing`() {
        val insights = useCase(
            snapshot(expense = 10_200.0, previousExpense = 10_000.0, count = 12),
            afterPeriod
        )
        assertTrue("2% is noise, not news: $insights", insights.isEmpty())
    }

    // ── Overall movement ──────────────────────────────────────────────────────

    @Test
    fun `a sharp rise in spending is reported as a warning`() {
        val insights = useCase(
            snapshot(expense = 15_000.0, previousExpense = 10_000.0, count = 20),
            afterPeriod
        )

        val movement = insights.single { it.title.startsWith("Spending is up") }
        assertEquals("Spending is up 50%", movement.title)
        assertEquals(InsightTone.WARNING, movement.tone)
    }

    @Test
    fun `a fall in spending reads as positive`() {
        val insights = useCase(
            snapshot(expense = 6_000.0, previousExpense = 10_000.0, count = 20),
            afterPeriod
        )

        val movement = insights.single { it.title.startsWith("Spending is down") }
        assertEquals(InsightTone.POSITIVE, movement.tone)
    }

    // ── Income ────────────────────────────────────────────────────────────────

    @Test
    fun `spending past income is the first thing said`() {
        val insights = useCase(
            snapshot(expense = 50_000.0, income = 40_000.0, count = 30),
            afterPeriod
        )

        assertEquals("Spending outran income", insights.first().title)
        assertEquals(InsightTone.WARNING, insights.first().tone)
    }

    @Test
    fun `a healthy savings rate is reported`() {
        val insights = useCase(
            snapshot(expense = 60_000.0, income = 100_000.0, count = 30),
            afterPeriod
        )

        assertTrue(insights.any { it.title == "You kept 40% of what came in" })
    }

    /** No income recorded is not the same as having saved nothing. */
    @Test
    fun `no income means no savings claim either way`() {
        val insights = useCase(snapshot(expense = 60_000.0, count = 30), afterPeriod)

        assertTrue(insights.none { it.title.contains("kept") })
        assertTrue(insights.none { it.title.contains("outran") })
    }

    // ── Pace ──────────────────────────────────────────────────────────────────

    @Test
    fun `a period still running is projected forward`() {
        // 11 days in at 1,000 a day projects to 31,000 across the month.
        val insights = useCase(snapshot(expense = 11_000.0, count = 11), midPeriod)

        val pace = insights.single { it.title.startsWith("On course for") }
        assertTrue(pace.title, pace.title.contains("31,000"))
        assertTrue(pace.description.contains("11 of 31 days in"))
    }

    /** Projecting a finished period would only restate a total already on screen. */
    @Test
    fun `a finished period is not projected`() {
        val insights = useCase(snapshot(expense = 11_000.0, count = 11), afterPeriod)
        assertTrue(insights.none { it.title.startsWith("On course for") })
    }

    // ── Categories ────────────────────────────────────────────────────────────

    @Test
    fun `the biggest mover is named with both figures`() {
        val insights = useCase(
            snapshot(
                expense = 20_000.0,
                previousExpense = 15_000.0,
                count = 40,
                comparisons = listOf(
                    CategoryComparison(ExpenseCategory.Food, current = 12_000.0, previous = 6_000.0)
                )
            ),
            afterPeriod
        )

        val mover = insights.single { it.title == "Food moved the most" }
        assertTrue(mover.description, mover.description.startsWith("Up 100%"))
    }

    @Test
    fun `a category that stopped is reported as a win`() {
        val insights = useCase(
            snapshot(
                expense = 5_000.0,
                count = 10,
                comparisons = listOf(
                    CategoryComparison(ExpenseCategory.Entertainment, current = 0.0, previous = 3_000.0)
                )
            ),
            afterPeriod
        )

        val stopped = insights.single { it.title.startsWith("Nothing on") }
        assertEquals(InsightTone.POSITIVE, stopped.tone)
    }

    /** Percentages on trivial sums are dramatic and meaningless. */
    @Test
    fun `tiny category movements are ignored`() {
        val insights = useCase(
            snapshot(
                expense = 5_000.0,
                count = 10,
                comparisons = listOf(
                    CategoryComparison(ExpenseCategory.Health, current = 60.0, previous = 10.0)
                )
            ),
            afterPeriod
        )

        assertTrue(insights.none { it.title.contains("Health") })
    }

    // ── Merchants ─────────────────────────────────────────────────────────────

    @Test
    fun `a dominant merchant is called out`() {
        val insights = useCase(
            snapshot(
                expense = 20_000.0,
                count = 30,
                merchants = listOf(MerchantSpend("Amazon", total = 9_000.0, transactionCount = 6))
            ),
            afterPeriod
        )

        assertTrue(insights.any { it.title == "45% of it went to Amazon" })
    }

    @Test
    fun `an evenly spread merchant is not called out`() {
        val insights = useCase(
            snapshot(
                expense = 20_000.0,
                count = 30,
                merchants = listOf(MerchantSpend("Amazon", total = 2_000.0, transactionCount = 3))
            ),
            afterPeriod
        )

        assertTrue(insights.none { it.title.contains("went to") })
    }

    /** Forty small orders look identical to one big purchase on every chart. */
    @Test
    fun `small and frequent spending is surfaced`() {
        val insights = useCase(
            snapshot(
                expense = 40_000.0,
                count = 60,
                merchants = listOf(MerchantSpend("Swiggy", total = 8_000.0, transactionCount = 40))
            ),
            afterPeriod
        )

        val frequent = insights.single { it.title == "40 visits to Swiggy" }
        assertTrue(frequent.description, frequent.description.startsWith("₹200.00 a time"))
    }

    // ── Outlier days ──────────────────────────────────────────────────────────

    @Test
    fun `a day far above the usual is named`() {
        val insights = useCase(
            snapshot(
                expense = 14_000.0,
                count = 8,
                days = mapOf(
                    "2026-08-02" to 1_000.0,
                    "2026-08-03" to 1_000.0,
                    "2026-08-04" to 1_000.0,
                    "2026-08-14" to 11_000.0
                )
            ),
            afterPeriod
        )

        val outlier = insights.single { it.title.contains("stands out") }
        // Compared against the formatter's own output rather than a literal, so
        // the assertion does not pin the suite to an English locale.
        assertTrue(outlier.title, outlier.title.startsWith(TimeBuckets.fullLabel("2026-08-14")))
        assertTrue(outlier.description, outlier.description.contains("11×"))
    }

    @Test
    fun `evenly spread days produce no outlier`() {
        val insights = useCase(
            snapshot(
                expense = 4_400.0,
                count = 4,
                days = mapOf(
                    "2026-08-02" to 1_000.0,
                    "2026-08-03" to 1_100.0,
                    "2026-08-04" to 1_200.0,
                    "2026-08-05" to 1_100.0
                )
            ),
            afterPeriod
        )

        assertTrue(insights.none { it.title.contains("stands out") })
    }

    // ── Weekends ──────────────────────────────────────────────────────────────

    @Test
    fun `a heavy weekend pattern is reported`() {
        val occurrences = WeekdayPattern.weekdayOccurrences(period.range(zone), zone)
        val totals = occurrences.mapValues { (index, count) ->
            count * if (index == 0 || index == 6) 400.0 else 100.0
        }

        val insights = useCase(
            snapshot(expense = totals.values.sum(), count = 31, weekday = totals),
            afterPeriod
        )

        val weekend = insights.single { it.title == "Weekends cost you more" }
        assertTrue(weekend.description, weekend.description.contains("300%"))
    }

    @Test
    fun `a level week produces no weekend insight`() {
        val occurrences = WeekdayPattern.weekdayOccurrences(period.range(zone), zone)
        val totals = occurrences.mapValues { (_, count) -> count * 100.0 }

        val insights = useCase(
            snapshot(expense = totals.values.sum(), count = 31, weekday = totals),
            afterPeriod
        )

        assertTrue(insights.none { it.title.contains("Weekend") })
    }

    // ── Ranking ───────────────────────────────────────────────────────────────

    @Test
    fun `the list is capped so it stays readable`() {
        val occurrences = WeekdayPattern.weekdayOccurrences(period.range(zone), zone)
        val everything = useCase(
            snapshot(
                expense = 50_000.0,
                income = 40_000.0,
                previousExpense = 20_000.0,
                count = 60,
                comparisons = listOf(
                    CategoryComparison(ExpenseCategory.Food, 20_000.0, 5_000.0),
                    CategoryComparison(ExpenseCategory.Travel, 8_000.0, 0.0),
                    CategoryComparison(ExpenseCategory.Bills, 0.0, 4_000.0)
                ),
                merchants = listOf(MerchantSpend("Swiggy", 20_000.0, 40)),
                days = mapOf(
                    "2026-08-02" to 1_000.0,
                    "2026-08-03" to 1_000.0,
                    "2026-08-04" to 1_000.0,
                    "2026-08-14" to 20_000.0
                ),
                weekday = occurrences.mapValues { (index, count) ->
                    count * if (index == 0 || index == 6) 400.0 else 100.0
                }
            ),
            afterPeriod
        )

        assertEquals(5, everything.size)
        // The urgent one still leads despite everything else competing.
        assertEquals("Spending outran income", everything.first().title)
    }

    @Test
    fun `every insight carries a description`() {
        val insights = useCase(
            snapshot(expense = 15_000.0, previousExpense = 10_000.0, count = 20),
            afterPeriod
        )

        assertTrue(insights.isNotEmpty())
        assertTrue(insights.all { it.title.isNotBlank() && it.description.isNotBlank() })
    }
}
