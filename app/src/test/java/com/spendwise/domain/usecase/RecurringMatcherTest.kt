package com.spendwise.domain.usecase

import com.spendwise.data.local.ExpenseTimeSeriesRow
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.RecurringCadence
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.model.RecurringSchedule
import com.spendwise.domain.model.RecurringStatus
import com.spendwise.domain.model.RecurringType
import com.spendwise.domain.model.TransactionNature
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attributing payments that have gone out to the commitments they settle.
 *
 * The reason this exists is the reminder that says rent is due in two days when
 * it was paid last week. Getting it wrong in the other direction is worse: a
 * payment wrongly attributed moves a commitment's due date, so the real one goes
 * unmentioned.
 */
class RecurringMatcherTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private var nextId = 1

    private fun millis(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun entry(
        id: Int = 1,
        title: String = "Netflix",
        amount: Double = 649.0,
        cadence: RecurringCadence = RecurringCadence.MONTHLY,
        type: RecurringType = RecurringType.FIXED,
        nature: TransactionNature = TransactionNature.Spending,
        currency: Currency = Currency.INR,
        status: RecurringStatus = RecurringStatus.ACTIVE,
        lastOccurrence: LocalDate? = LocalDate.of(2026, 7, 5),
        nextDue: LocalDate? = LocalDate.of(2026, 8, 5),
        dueDayOfMonth: Int? = 5
    ) = RecurringEntry(
        id = id,
        title = title,
        amount = amount,
        cadence = cadence,
        type = type,
        currency = currency,
        nature = nature,
        category = ExpenseCategory.Subscriptions,
        status = status,
        lastOccurrenceDate = lastOccurrence?.let { millis(it) },
        nextDueDate = nextDue?.let { millis(it) },
        dueDayOfMonth = dueDayOfMonth
    )

    private fun payment(
        merchant: String = "Netflix",
        date: LocalDate = LocalDate.of(2026, 8, 5),
        amount: Double = 649.0,
        nature: TransactionNature = TransactionNature.Spending,
        currency: Currency = Currency.INR
    ) = ExpenseTimeSeriesRow(
        expenseId = nextId++,
        merchant = merchant,
        date = millis(date),
        amount = amount,
        category = ExpenseCategory.Subscriptions.label,
        nature = nature.name,
        currency = currency.code
    )

    // ── The case it exists for ────────────────────────────────────────────────

    @Test
    fun `a payment on the expected date settles the commitment`() {
        val paid = payment(date = LocalDate.of(2026, 8, 5))

        val matches = RecurringMatcher.match(listOf(entry()), listOf(paid))

        assertEquals(1, matches.size)
        assertEquals(listOf(paid.expenseId), matches.single().expenseIds)
        assertEquals(millis(LocalDate.of(2026, 8, 5)), matches.single().lastOccurrenceDate)
    }

    /** Settling this month's payment must move the due date to next month's. */
    @Test
    fun `settling a payment advances the due date`() {
        val matches = RecurringMatcher.match(
            listOf(entry()),
            listOf(payment(date = LocalDate.of(2026, 8, 5)))
        )

        assertEquals(
            millis(LocalDate.of(2026, 9, 5)),
            matches.single().nextDueDate
        )
    }

    @Test
    fun `a payment a few days early or late still counts`() {
        val early = RecurringMatcher.match(
            listOf(entry()),
            listOf(payment(date = LocalDate.of(2026, 8, 2)))
        )
        val late = RecurringMatcher.match(
            listOf(entry()),
            listOf(payment(date = LocalDate.of(2026, 8, 9)))
        )

        assertEquals(1, early.size)
        assertEquals(1, late.size)
    }

    /**
     * The anchor day has to survive settlement too. A payment made on the 3rd
     * because the 5th was a Sunday must not move the commitment to the 3rd for
     * good.
     */
    @Test
    fun `an early payment does not move the anchor day`() {
        val matches = RecurringMatcher.match(
            listOf(entry(dueDayOfMonth = 5)),
            listOf(payment(date = LocalDate.of(2026, 8, 3)))
        )

        assertEquals(millis(LocalDate.of(2026, 9, 5)), matches.single().nextDueDate)
    }

    // ── Refusing to attribute the wrong payment ───────────────────────────────

    @Test
    fun `a payment half a cycle away belongs to another cycle`() {
        val matches = RecurringMatcher.match(
            listOf(entry()),
            listOf(payment(date = LocalDate.of(2026, 8, 25)))
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `a different merchant is never attributed`() {
        val matches = RecurringMatcher.match(
            listOf(entry(title = "Netflix")),
            listOf(payment(merchant = "Spotify"))
        )

        assertTrue(matches.isEmpty())
    }

    /** A ₹200 purchase does not settle a ₹649 subscription. */
    @Test
    fun `a fixed commitment rejects a wildly different amount`() {
        val matches = RecurringMatcher.match(
            listOf(entry(amount = 649.0, type = RecurringType.FIXED)),
            listOf(payment(amount = 200.0))
        )

        assertTrue(matches.isEmpty())
    }

    /** A modest price rise is still the same subscription. */
    @Test
    fun `a fixed commitment accepts a small price rise`() {
        val matches = RecurringMatcher.match(
            listOf(entry(amount = 649.0, type = RecurringType.FIXED)),
            listOf(payment(amount = 699.0))
        )

        assertEquals(1, matches.size)
    }

    /** An electricity bill genuinely doubles between seasons. */
    @Test
    fun `a variable commitment tolerates a much larger swing`() {
        val matches = RecurringMatcher.match(
            listOf(entry(amount = 1_200.0, type = RecurringType.VARIABLE)),
            listOf(payment(amount = 2_400.0))
        )

        assertEquals(1, matches.size)
    }

    @Test
    fun `currency and nature must both agree`() {
        val wrongCurrency = RecurringMatcher.match(
            listOf(entry(currency = Currency.INR)),
            listOf(payment(currency = Currency.USD, amount = 649.0))
        )
        val wrongNature = RecurringMatcher.match(
            listOf(entry(nature = TransactionNature.Spending)),
            listOf(payment(nature = TransactionNature.Investment))
        )

        assertTrue(wrongCurrency.isEmpty())
        assertTrue(wrongNature.isEmpty())
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * A paused commitment absorbing a payment would move its due date and bring
     * it back to life in every total that reads it — which is precisely what
     * pausing was meant to prevent.
     */
    @Test
    fun `paused and ended commitments absorb nothing`() {
        val paused = RecurringMatcher.match(
            listOf(entry(status = RecurringStatus.PAUSED)),
            listOf(payment())
        )
        val ended = RecurringMatcher.match(
            listOf(entry(status = RecurringStatus.ENDED)),
            listOf(payment())
        )

        assertTrue(paused.isEmpty())
        assertTrue(ended.isEmpty())
    }

    // ── One payment, one commitment ───────────────────────────────────────────

    /**
     * Two similar commitments must not both claim the same charge: each would
     * move its own due date on one payment, and one of them would then go quiet
     * for a cycle it had not actually been paid for.
     */
    @Test
    fun `a payment settles only one commitment`() {
        val entries = listOf(
            entry(id = 1, title = "Netflix"),
            entry(id = 2, title = "Netflix")
        )

        val matches = RecurringMatcher.match(entries, listOf(payment()))

        assertEquals(1, matches.size)
    }

    @Test
    fun `several payments in the window all attach, and the latest sets the date`() {
        val first = payment(date = LocalDate.of(2026, 8, 3))
        val second = payment(date = LocalDate.of(2026, 8, 7))

        val matches = RecurringMatcher.match(listOf(entry()), listOf(first, second))

        assertEquals(2, matches.single().expenseIds.size)
        assertEquals(millis(LocalDate.of(2026, 8, 7)), matches.single().lastOccurrenceDate)
    }

    // ── A commitment still finding its rhythm ─────────────────────────────────

    /**
     * A manually added commitment has no projected date yet, so there is nothing
     * to compare a payment against. Anything after the last known payment is
     * accepted; refusing everything would leave it permanently unable to learn.
     */
    @Test
    fun `a commitment with no projected date accepts a later payment`() {
        val matches = RecurringMatcher.match(
            listOf(entry(lastOccurrence = LocalDate.of(2026, 7, 1), nextDue = null)),
            listOf(payment(date = LocalDate.of(2026, 8, 5)))
        )

        assertEquals(1, matches.size)
    }

    @Test
    fun `a commitment with no history at all accepts its first payment`() {
        val matches = RecurringMatcher.match(
            listOf(entry(lastOccurrence = null, nextDue = null)),
            listOf(payment())
        )

        assertEquals(1, matches.size)
    }

    @Test
    fun `nothing to match produces nothing`() {
        assertTrue(RecurringMatcher.match(emptyList(), listOf(payment())).isEmpty())
        assertTrue(RecurringMatcher.match(listOf(entry()), emptyList()).isEmpty())
    }

    /** Weekly commitments get a weekly window, not a monthly one. */
    @Test
    fun `the window scales with the cadence`() {
        val weekly = entry(
            cadence = RecurringCadence.WEEKLY,
            amount = 800.0,
            lastOccurrence = LocalDate.of(2026, 7, 29),
            nextDue = LocalDate.of(2026, 8, 5),
            dueDayOfMonth = null
        )

        val onTime = RecurringMatcher.match(weekly.let(::listOf), listOf(payment(amount = 800.0, date = LocalDate.of(2026, 8, 6))))
        val tooFar = RecurringMatcher.match(weekly.let(::listOf), listOf(payment(amount = 800.0, date = LocalDate.of(2026, 8, 12))))

        assertEquals(1, onTime.size)
        assertTrue("a payment a week out is next week's", tooFar.isEmpty())
    }

    @Test
    fun `the projected date uses calendar arithmetic`() {
        val monthEnd = entry(
            amount = 22_000.0,
            lastOccurrence = LocalDate.of(2026, 1, 31),
            nextDue = LocalDate.of(2026, 2, 28),
            dueDayOfMonth = 31
        )

        val matches = RecurringMatcher.match(
            listOf(monthEnd),
            listOf(payment(amount = 22_000.0, date = LocalDate.of(2026, 2, 28)))
        )

        assertEquals(
            RecurringSchedule.nextDueDateMillis(
                lastOccurrence = millis(LocalDate.of(2026, 2, 28)),
                cadence = RecurringCadence.MONTHLY,
                dueDayOfMonth = 31
            ),
            matches.single().nextDueDate
        )
        assertEquals(millis(LocalDate.of(2026, 3, 31)), matches.single().nextDueDate)
    }
}
