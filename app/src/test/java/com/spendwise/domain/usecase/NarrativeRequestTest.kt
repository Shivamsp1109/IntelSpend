package com.spendwise.domain.usecase

import com.spendwise.domain.model.AnalyticsPeriod
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.DateRange
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.Insight
import com.spendwise.domain.model.MerchantSpend
import com.spendwise.domain.model.PeriodType
import com.spendwise.domain.model.SpendingSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * What actually leaves the device when a summary is requested.
 *
 * The claim on the settings toggle is that totals and merchant names are sent
 * and individual transactions are not. This is where that claim is checked, so
 * a later change that starts attaching rows has to fail a test rather than
 * quietly widen what gets shared.
 */
class NarrativeRequestTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val period = AnalyticsPeriod(PeriodType.MONTH, LocalDate.of(2026, 8, 1))

    private fun snapshot(
        merchants: List<MerchantSpend> = emptyList(),
        categories: Map<ExpenseCategory, Double> = emptyMap(),
        insights: List<Insight> = emptyList()
    ) = AnalyticsSnapshot(
        period = period,
        currency = Currency.INR,
        summary = SpendingSummary(
            currency = Currency.INR,
            range = period.range(zone),
            totalExpense = 40_000.0,
            totalIncome = 60_000.0,
            previousExpense = 35_000.0,
            previousIncome = 55_000.0,
            transactionCount = 62
        ),
        byCategory = categories,
        topMerchants = merchants,
        insights = insights
    )

    @Test
    fun `the totals are carried across`() {
        val request = narrativeRequestFor(snapshot())

        assertEquals("INR", request.currency)
        assertEquals(40_000.0, request.totalExpense, 0.0001)
        assertEquals(60_000.0, request.totalIncome, 0.0001)
        assertEquals(35_000.0, request.previousExpense, 0.0001)
        assertEquals(62, request.transactionCount)
    }

    /** A label like "Today" would be meaningless to a model summarising a month. */
    @Test
    fun `the period is named factually`() {
        assertEquals(period.displayLabel(), narrativeRequestFor(snapshot()).periodLabel)
        assertTrue(narrativeRequestFor(snapshot()).periodLabel.contains("2026"))
    }

    @Test
    fun `categories go in largest first`() {
        val request = narrativeRequestFor(
            snapshot(
                categories = mapOf(
                    ExpenseCategory.Travel to 3_000.0,
                    ExpenseCategory.Food to 12_000.0,
                    ExpenseCategory.Bills to 7_000.0
                )
            )
        )

        assertEquals(listOf("Food", "Bills", "Travel"), request.topCategories.map { it.name })
    }

    /** Beyond a handful this is padding the prompt, and the user pays per token. */
    @Test
    fun `the lists are capped at five`() {
        val request = narrativeRequestFor(
            snapshot(
                categories = ExpenseCategory.entries.associateWith { 1_000.0 },
                merchants = (1..12).map { MerchantSpend("Shop $it", 500.0, 2) }
            )
        )

        assertEquals(5, request.topCategories.size)
        assertEquals(5, request.topMerchants.size)
    }

    @Test
    fun `merchant frequency travels with the amount`() {
        val request = narrativeRequestFor(
            snapshot(merchants = listOf(MerchantSpend("Swiggy", 8_000.0, 40)))
        )

        val swiggy = request.topMerchants.single()
        assertEquals("Swiggy", swiggy.name)
        assertEquals(8_000.0, swiggy.amount, 0.0001)
        assertEquals(40, swiggy.count)
    }

    /**
     * The rule-based findings are sent so the model describes conclusions the
     * app already stands behind, rather than reinterpreting the raw totals and
     * contradicting the cards above it on screen.
     */
    @Test
    fun `the app's own findings are passed along`() {
        val request = narrativeRequestFor(
            snapshot(
                insights = listOf(
                    Insight("Spending is up 14%", "₹5,000 more than the period before.")
                )
            )
        )

        assertEquals(1, request.highlights.size)
        assertTrue(request.highlights.single().startsWith("Spending is up 14%"))
    }

    /**
     * The important negative. The request type has no field for a transaction,
     * so this asserts on the whole serialised shape rather than on fields — a
     * new field carrying rows would change this and fail here.
     */
    @Test
    fun `nothing beyond aggregates is present in the payload`() {
        val request = narrativeRequestFor(
            snapshot(
                categories = mapOf(ExpenseCategory.Health to 4_000.0),
                merchants = listOf(MerchantSpend("Clinic", 4_000.0, 1))
            )
        )

        // The Compose compiler adds a synthetic `$stable` field to classes it
        // sees; that is machinery, not data, so it does not belong in the check.
        val fields = request.javaClass.declaredFields
            .filterNot { it.isSynthetic || it.name.startsWith("$") }
            .map { it.name }
            .toSet()

        assertEquals(
            setOf(
                "periodLabel", "currency", "totalExpense", "totalIncome", "previousExpense",
                "averagePerDay", "transactionCount", "topCategories", "topMerchants", "highlights"
            ),
            fields
        )
    }

    @Test
    fun `the average per day comes from the period's own length`() {
        val request = narrativeRequestFor(snapshot())

        // 40,000 across a 31-day August.
        assertEquals(40_000.0 / 31, request.averagePerDay, 0.01)
        assertEquals(31, DateRange(period.range(zone).start, period.range(zone).end).dayCount)
    }
}
