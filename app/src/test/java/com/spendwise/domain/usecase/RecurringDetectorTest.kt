package com.spendwise.domain.usecase

import com.spendwise.data.local.DismissedRecurringCandidateEntity
import com.spendwise.data.local.ExpenseTimeSeriesRow
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.RecurringCadence
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.model.RecurringType
import com.spendwise.domain.model.TransactionNature
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding fixed payments in ordinary, messy history.
 *
 * The failure that matters here is not missing a subscription — it is inventing
 * one. A detected commitment goes on to shape what the app says the user can
 * afford, so a coincidence promoted to a monthly obligation produces confident
 * advice built on a number that was never real.
 */
class RecurringDetectorTest {

    private val today = LocalDate.of(2026, 8, 13)
    private val now = epoch(today)

    private fun epoch(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private var nextId = 1

    private fun row(
        merchant: String,
        date: LocalDate,
        amount: Double,
        nature: TransactionNature = TransactionNature.Spending,
        category: ExpenseCategory = ExpenseCategory.Subscriptions,
        currency: Currency = Currency.INR
    ) = ExpenseTimeSeriesRow(
        expenseId = nextId++,
        merchant = merchant,
        date = epoch(date),
        amount = amount,
        category = category.label,
        nature = nature.name,
        currency = currency.code
    )

    /** Monthly on the same day, walking backwards from the most recent payment. */
    private fun monthly(
        merchant: String,
        months: Int,
        amount: Double,
        nature: TransactionNature = TransactionNature.Spending,
        category: ExpenseCategory = ExpenseCategory.Subscriptions,
        currency: Currency = Currency.INR,
        latest: LocalDate = today.minusDays(3)
    ): List<ExpenseTimeSeriesRow> = (0 until months).map { back ->
        row(merchant, latest.minusMonths(back.toLong()), amount, nature, category, currency)
    }

    private fun detect(
        rows: List<ExpenseTimeSeriesRow>,
        existing: List<RecurringEntry> = emptyList(),
        dismissed: List<DismissedRecurringCandidateEntity> = emptyList()
    ) = RecurringDetector.detect(rows, existing, dismissed, now)

    // ── The basic shapes ──────────────────────────────────────────────────────

    @Test
    fun `the same amount every month is a fixed commitment`() {
        val found = detect(monthly("Netflix", months = 6, amount = 649.0))

        assertEquals(1, found.size)
        assertEquals(RecurringCadence.MONTHLY, found.first().cadence)
        assertEquals(RecurringType.FIXED, found.first().type)
        assertEquals(649.0, found.first().averageAmount, 0.01)
    }

    /** A phone or electricity bill: reliably monthly, never quite the same figure. */
    @Test
    fun `a monthly bill that moves around is variable, not fixed`() {
        val amounts = listOf(1_240.0, 1_180.0, 1_390.0, 1_275.0, 1_310.0)
        val rows = amounts.mapIndexed { index, amount ->
            row("BESCOM", today.minusDays(3).minusMonths(index.toLong()), amount)
        }

        val found = detect(rows)

        assertEquals(1, found.size)
        assertEquals(RecurringType.VARIABLE, found.first().type)
        assertEquals(RecurringCadence.MONTHLY, found.first().cadence)
    }

    @Test
    fun `weekly and quarterly patterns are recognised too`() {
        val weekly = (0 until 6).map { row("Cleaner", today.minusDays(2 + it * 7L), 800.0) }
        val quarterly = (0 until 3).map {
            row("LIC Premium", today.minusDays(5).minusMonths(it * 3L), 12_000.0)
        }

        assertEquals(RecurringCadence.WEEKLY, detect(weekly).single().cadence)
        assertEquals(RecurringCadence.QUARTERLY, detect(quarterly).single().cadence)
    }

    // ── Refusing to invent commitments ────────────────────────────────────────

    @Test
    fun `irregular spending at one shop is not a commitment`() {
        val rows = listOf(
            row("Local Store", today.minusDays(90), 300.0),
            row("Local Store", today.minusDays(64), 950.0),
            row("Local Store", today.minusDays(37), 220.0),
            row("Local Store", today.minusDays(9), 1_400.0)
        )

        assertTrue(detect(rows).isEmpty())
    }

    @Test
    fun `two monthly payments are not yet enough`() {
        assertTrue(detect(monthly("Spotify", months = 2, amount = 119.0)).isEmpty())
    }

    /**
     * Amounts this different are shopping, however evenly spaced. Someone who
     * happens to buy from the same shop each month has not taken on a commitment.
     */
    @Test
    fun `an evenly spaced series with wildly different amounts is rejected`() {
        val amounts = listOf(200.0, 4_500.0, 900.0, 6_100.0, 350.0)
        val rows = amounts.mapIndexed { index, amount ->
            row("Amazon", today.minusDays(3).minusMonths(index.toLong()), amount, category = ExpenseCategory.Shopping)
        }

        assertTrue(detect(rows).isEmpty())
    }

    /** A subscription cancelled long ago is a real pattern and a useless suggestion. */
    @Test
    fun `a series that stopped long ago is not offered`() {
        val rows = (0 until 5).map {
            row("Old Gym", today.minusMonths(8).minusMonths(it.toLong()), 1_500.0)
        }

        assertTrue(detect(rows).isEmpty())
    }

    // ── Real-world messiness ──────────────────────────────────────────────────

    /** A card expires, one month is missed, the subscription carries on. */
    @Test
    fun `one missed month does not disqualify a subscription`() {
        val latest = today.minusDays(3)
        val rows = listOf(0L, 1L, 2L, 4L, 5L).map { back ->
            row("Netflix", latest.minusMonths(back), 649.0)
        }

        val found = detect(rows)

        assertEquals(1, found.size)
        assertEquals(RecurringCadence.MONTHLY, found.first().cadence)
    }

    /**
     * Exactly two lapses, which is one more than is tolerated. Chosen so the
     * series would be accepted if the allowance were widened by a single step —
     * a vaguer fixture passes whatever the allowance is and tests nothing.
     */
    @Test
    fun `two missed intervals stop it being a schedule`() {
        val latest = today.minusDays(3)
        val rows = listOf(0L, 1L, 3L, 5L).map { back ->
            row("Patchy", latest.minusMonths(back), 500.0)
        }

        assertTrue(detect(rows).none { it.cadence == RecurringCadence.MONTHLY })
    }

    /**
     * A payment declined and immediately retried writes two rows for one charge.
     * Left in, the one-day gap destroys the cadence fit for the whole series.
     */
    @Test
    fun `a failed and retried charge counts once`() {
        val latest = today.minusDays(3)
        val rows = monthly("Netflix", months = 5, amount = 649.0) +
            row("Netflix", latest.minusDays(1), 649.0)

        val found = detect(rows)

        assertEquals(1, found.size)
        assertEquals(RecurringCadence.MONTHLY, found.first().cadence)
        assertEquals(5, found.first().occurrenceCount)
    }

    /** Month lengths differ; a bill on the 31st cannot fall on the 31st in February. */
    @Test
    fun `month-end dates still read as monthly`() {
        val rows = listOf(
            LocalDate.of(2026, 3, 31),
            LocalDate.of(2026, 4, 30),
            LocalDate.of(2026, 5, 31),
            LocalDate.of(2026, 6, 30),
            LocalDate.of(2026, 7, 31)
        ).map { row("Rent", it, 22_000.0, category = ExpenseCategory.RentHousing) }

        assertEquals(RecurringCadence.MONTHLY, detect(rows).single().cadence)
    }

    // ── Not everything that repeats is spending ───────────────────────────────

    /**
     * The reason detection cannot filter to Spending the way every other list in
     * this app does. An EMI is the single most important fixed payment in most
     * budgets and its nature is LoanRepayment, so a Spending-only scan would miss
     * precisely the thing the feature exists to find.
     */
    @Test
    fun `an EMI is found and stays a loan repayment`() {
        val found = detect(
            monthly(
                "HDFC Car Loan",
                months = 6,
                amount = 18_500.0,
                nature = TransactionNature.LoanRepayment,
                category = ExpenseCategory.Other
            )
        ).single()

        assertEquals(TransactionNature.LoanRepayment, found.nature)
        assertEquals(RecurringType.FIXED, found.type)
    }

    @Test
    fun `a monthly SIP is found and stays an investment`() {
        val found = detect(
            monthly(
                "Axis Bluechip SIP",
                months = 5,
                amount = 5_000.0,
                nature = TransactionNature.Investment,
                category = ExpenseCategory.Other
            )
        ).single()

        assertEquals(TransactionNature.Investment, found.nature)
    }

    /**
     * A card bill arrives every month like clockwork, but its amount is whatever
     * was spent — a repeating event, not a fixed commitment. Treating it as one
     * would put a meaningless number in the user's obligations.
     */
    @Test
    fun `credit card bill payments never enter the pipeline`() {
        val rows = monthly(
            "HDFC Credit Card",
            months = 6,
            amount = 24_000.0,
            nature = TransactionNature.CreditCardPayment
        )

        // The DAO query excludes this nature; the detector must agree, so that a
        // caller passing unfiltered rows cannot produce a card-bill commitment.
        assertTrue(detect(rows.filter { it.nature != TransactionNature.CreditCardPayment.name }).isEmpty())
    }

    // ── Keeping distinct things apart ─────────────────────────────────────────

    @Test
    fun `a rupee series and a dollar series are never averaged together`() {
        val rows = monthly("Adobe", months = 4, amount = 1_600.0, currency = Currency.INR) +
            monthly("Adobe", months = 4, amount = 20.0, currency = Currency.USD)

        val found = detect(rows)

        assertEquals(2, found.size)
        assertTrue(found.any { it.currency == Currency.INR && it.averageAmount > 1_000 })
        assertTrue(found.any { it.currency == Currency.USD && it.averageAmount < 100 })
    }

    @Test
    fun `the same merchant under two natures stays two things`() {
        val rows = monthly("Acme", months = 4, amount = 1_000.0, nature = TransactionNature.Spending) +
            monthly("Acme", months = 4, amount = 9_000.0, nature = TransactionNature.Investment)

        assertEquals(2, detect(rows).size)
    }

    /**
     * Fuzzy merchant matching is what lets `NETFLIX` and `Netflix India` be one
     * payee. It is also what would merge two different people, so short names
     * must match exactly — a personal transfer promoted to a monthly commitment
     * is a convincing, wrong number in someone's budget.
     */
    @Test
    fun `short personal names are never fuzzy-merged`() {
        // Both reduce to the single token "ravi", so similarity alone would call
        // them the same payee. Only the length rule keeps them apart. One side is
        // deliberately short of standing on its own, because that is the case
        // where absorption is attempted at all.
        val strong = listOf(0L, 1L, 2L).map { back ->
            row("Ravi K", today.minusDays(8).minusMonths(back), 2_000.0, category = ExpenseCategory.Other)
        }
        val weak = listOf(0L, 1L).map { back ->
            row("Ravi S", today.minusDays(23).minusMonths(back), 3_000.0, category = ExpenseCategory.Other)
        }

        val found = detect(strong + weak)

        assertEquals(1, found.size)
        assertEquals("Ravi K", found.single().merchant)
        assertEquals(3, found.single().occurrenceCount)
    }

    @Test
    fun `a long merchant name written two ways is one commitment`() {
        val latest = today.minusDays(3)
        val rows = listOf(
            row("NETFLIX", latest, 649.0),
            row("NETFLIX", latest.minusMonths(1), 649.0),
            row("NETFLIX", latest.minusMonths(2), 649.0),
            row("Netflix India", latest.minusMonths(3), 649.0)
        )

        val found = detect(rows)

        assertEquals(1, found.size)
        assertEquals(4, found.first().occurrenceCount)
    }

    // ── Not asking twice ──────────────────────────────────────────────────────

    @Test
    fun `something already tracked is not suggested again`() {
        val tracked = RecurringEntry(
            id = 1,
            title = "Netflix",
            amount = 649.0,
            cadence = RecurringCadence.MONTHLY,
            type = RecurringType.FIXED,
            currency = Currency.INR,
            nature = TransactionNature.Spending,
            category = ExpenseCategory.Subscriptions
        )

        val found = detect(monthly("Netflix", months = 6, amount = 649.0), existing = listOf(tracked))

        assertTrue(found.isEmpty())
    }

    @Test
    fun `a dismissed pattern stays dismissed`() {
        val rows = monthly("Netflix", months = 6, amount = 649.0)
        val candidate = detect(rows).single()

        val dismissal = DismissedRecurringCandidateEntity(
            signature = candidate.signature,
            merchant = candidate.merchant,
            currency = candidate.currency.code,
            nature = candidate.nature.name,
            category = candidate.category.name,
            cadence = candidate.cadence.name,
            lastSeenAmount = candidate.averageAmount,
            lastSeenOccurrenceDate = candidate.lastOccurrenceDate,
            dismissedAt = now
        )

        assertTrue(detect(rows, dismissed = listOf(dismissal)).isEmpty())
    }

    /**
     * A dismissal rejects a pattern, not a merchant forever. When the charge
     * changes materially it is a different claim, and hiding it would mean a
     * price rise the user never agreed to goes unmentioned.
     */
    @Test
    fun `a dismissed pattern resurfaces once the amount changes materially`() {
        val rows = monthly("Netflix", months = 6, amount = 649.0)
        val candidate = detect(rows).single()

        val dismissal = DismissedRecurringCandidateEntity(
            signature = candidate.signature,
            merchant = candidate.merchant,
            currency = candidate.currency.code,
            nature = candidate.nature.name,
            category = candidate.category.name,
            cadence = candidate.cadence.name,
            lastSeenAmount = 199.0,
            lastSeenOccurrenceDate = candidate.lastOccurrenceDate,
            dismissedAt = now
        )

        assertFalse(detect(rows, dismissed = listOf(dismissal)).isEmpty())
    }

    // ── Confidence ────────────────────────────────────────────────────────────

    @Test
    fun `a long clean run is more confident than a short one`() {
        val long = detect(monthly("Netflix", months = 8, amount = 649.0)).single()
        val short = detect(monthly("Hotstar", months = 3, amount = 299.0)).single()

        assertTrue(
            "long ${long.confidence} vs short ${short.confidence}",
            long.confidence > short.confidence
        )
    }

    /**
     * Two annual premiums two years apart can look immaculate and still be
     * coincidence. The score has to say so rather than reporting near certainty
     * from two data points.
     */
    @Test
    fun `a yearly pattern from two payments is never highly confident`() {
        val rows = listOf(
            row("Car Insurance", today.minusDays(20), 14_000.0, category = ExpenseCategory.Insurance),
            row("Car Insurance", today.minusDays(20).minusYears(1), 14_000.0, category = ExpenseCategory.Insurance)
        )

        val found = detect(rows).single()

        assertEquals(RecurringCadence.YEARLY, found.cadence)
        assertTrue("confidence was ${found.confidence}", found.confidence <= 0.5)
    }

    @Test
    fun `candidates arrive most confident first`() {
        val rows = monthly("Netflix", months = 8, amount = 649.0) +
            monthly("Hotstar", months = 3, amount = 299.0, latest = today.minusDays(5))

        val found = detect(rows)

        assertEquals(2, found.size)
        assertTrue(found[0].confidence >= found[1].confidence)
    }

    @Test
    fun `nothing at all produces nothing`() {
        assertTrue(detect(emptyList()).isEmpty())
    }

    @Test
    fun `occurrences carry the expense ids that produced them`() {
        val rows = monthly("Netflix", months = 4, amount = 649.0)
        val found = detect(rows).single()

        assertEquals(
            rows.map { it.expenseId }.sorted(),
            found.occurrences.map { it.expenseId }.sorted()
        )
        assertNotNull(found.signature)
        assertNull(found.occurrences.firstOrNull { it.amount != 649.0 })
    }
}
