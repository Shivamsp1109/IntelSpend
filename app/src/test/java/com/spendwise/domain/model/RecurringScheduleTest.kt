package com.spendwise.domain.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two calculations that look trivial and are not.
 *
 * Both produce plausible-looking numbers when wrong, which is what makes them
 * worth pinning: a due date that drifts a day each month and a monthly figure
 * that is off by a factor of four both pass a glance and quietly misinform.
 */
class RecurringScheduleTest {

    // ── Monthly equivalent ────────────────────────────────────────────────────

    @Test
    fun `a monthly amount is already monthly`() {
        assertEquals(1_500.0, RecurringSchedule.monthlyEquivalent(1_500.0, RecurringCadence.MONTHLY), 0.001)
    }

    @Test
    fun `a yearly premium spreads across twelve months`() {
        assertEquals(1_000.0, RecurringSchedule.monthlyEquivalent(12_000.0, RecurringCadence.YEARLY), 0.001)
    }

    @Test
    fun `a quarterly payment spreads across three`() {
        assertEquals(4_000.0, RecurringSchedule.monthlyEquivalent(12_000.0, RecurringCadence.QUARTERLY), 0.001)
    }

    /**
     * The one that is easy to get wrong. A week is not a quarter of a month:
     * 52 payments a year is 4.348 a month, and calling it four understates a
     * weekly commitment by roughly 8% — on a ₹2,000 weekly outgoing that is
     * ₹8,000 a year missing from what the user is told they owe.
     */
    @Test
    fun `a weekly amount uses the real number of weeks in a month`() {
        val monthly = RecurringSchedule.monthlyEquivalent(1_000.0, RecurringCadence.WEEKLY)

        assertEquals(4_348.0, monthly, 1.0)
        assertTrue("a week is not a quarter of a month", monthly > 4_000.0)
    }

    @Test
    fun `a fortnightly amount is half the weekly rate`() {
        val weekly = RecurringSchedule.monthlyEquivalent(1_000.0, RecurringCadence.WEEKLY)
        val fortnightly = RecurringSchedule.monthlyEquivalent(1_000.0, RecurringCadence.BIWEEKLY)

        assertEquals(weekly / 2, fortnightly, 0.001)
    }

    /**
     * Cadences must be comparable once normalised, which is the entire reason
     * this exists — a raw sum of a weekly and a yearly amount is meaningless.
     */
    @Test
    fun `different cadences become comparable`() {
        val weekly = RecurringSchedule.monthlyEquivalent(1_000.0, RecurringCadence.WEEKLY)
        val yearly = RecurringSchedule.monthlyEquivalent(12_000.0, RecurringCadence.YEARLY)

        assertTrue("₹1,000 a week costs more per month than ₹12,000 a year", weekly > yearly)
    }

    @Test
    fun `the entry extension agrees with the calculation`() {
        val entry = RecurringEntry(
            title = "Cleaner",
            amount = 800.0,
            cadence = RecurringCadence.WEEKLY,
            type = RecurringType.FIXED
        )

        assertEquals(
            RecurringSchedule.monthlyEquivalent(800.0, RecurringCadence.WEEKLY),
            entry.monthlyEquivalent,
            0.001
        )
    }

    // ── Next due date ─────────────────────────────────────────────────────────

    @Test
    fun `an ordinary month advances by one`() {
        assertEquals(
            LocalDate.of(2026, 9, 15),
            RecurringSchedule.nextDueDate(LocalDate.of(2026, 8, 15), RecurringCadence.MONTHLY)
        )
    }

    /**
     * February has no 31st. The projection has to land on the last day of the
     * month rather than throwing or rolling forward into March.
     */
    @Test
    fun `a month-end commitment lands on the last day of a shorter month`() {
        assertEquals(
            LocalDate.of(2026, 2, 28),
            RecurringSchedule.nextDueDate(LocalDate.of(2026, 1, 31), RecurringCadence.MONTHLY)
        )
    }

    @Test
    fun `a leap year gets its extra day`() {
        assertEquals(
            LocalDate.of(2028, 2, 29),
            RecurringSchedule.nextDueDate(LocalDate.of(2028, 1, 31), RecurringCadence.MONTHLY)
        )
    }

    /**
     * The reason the anchor day is stored rather than read off the last payment.
     *
     * A commitment due on the 31st is paid on the 28th in February. Projecting
     * the next date from *that* would leave it on the 28th of March, and on the
     * 28th every month after — one short month walking it permanently backwards
     * through the calendar.
     */
    @Test
    fun `the anchor day survives a short month`() {
        val afterFebruary = RecurringSchedule.nextDueDate(
            lastOccurrence = LocalDate.of(2026, 2, 28),
            cadence = RecurringCadence.MONTHLY,
            dueDayOfMonth = 31
        )

        assertEquals(LocalDate.of(2026, 3, 31), afterFebruary)
    }

    @Test
    fun `without an anchor the day would drift`() {
        // Documents the behaviour the anchor exists to prevent: with nothing to
        // anchor to, the last payment's day is all there is to go on.
        val drifted = RecurringSchedule.nextDueDate(
            lastOccurrence = LocalDate.of(2026, 2, 28),
            cadence = RecurringCadence.MONTHLY,
            dueDayOfMonth = null
        )

        assertEquals(LocalDate.of(2026, 3, 28), drifted)
    }

    @Test
    fun `weekly and fortnightly advance by whole weeks`() {
        assertEquals(
            LocalDate.of(2026, 8, 20),
            RecurringSchedule.nextDueDate(LocalDate.of(2026, 8, 13), RecurringCadence.WEEKLY)
        )
        assertEquals(
            LocalDate.of(2026, 8, 27),
            RecurringSchedule.nextDueDate(LocalDate.of(2026, 8, 13), RecurringCadence.BIWEEKLY)
        )
    }

    @Test
    fun `quarterly advances three months and yearly one year`() {
        assertEquals(
            LocalDate.of(2026, 11, 15),
            RecurringSchedule.nextDueDate(LocalDate.of(2026, 8, 15), RecurringCadence.QUARTERLY)
        )
        assertEquals(
            LocalDate.of(2027, 8, 15),
            RecurringSchedule.nextDueDate(LocalDate.of(2026, 8, 15), RecurringCadence.YEARLY)
        )
    }

    /** An annual premium taken on 29 February has no 29th to land on next year. */
    @Test
    fun `a leap day yearly commitment survives a common year`() {
        assertEquals(
            LocalDate.of(2029, 2, 28),
            RecurringSchedule.nextDueDate(LocalDate.of(2028, 2, 29), RecurringCadence.YEARLY)
        )
    }

    /**
     * Adding a fixed number of days is the mistake this replaces: thirty days
     * from 15 January is 14 February, a day early, and the error compounds every
     * month until rent is projected into the wrong month entirely.
     */
    @Test
    fun `projection does not drift the way fixed-day arithmetic would`() {
        var date = LocalDate.of(2026, 1, 15)
        repeat(12) {
            date = RecurringSchedule.nextDueDate(date, RecurringCadence.MONTHLY, dueDayOfMonth = 15)
        }

        assertEquals(LocalDate.of(2027, 1, 15), date)
    }

    @Test
    fun `an out-of-range anchor day is clamped rather than throwing`() {
        assertEquals(
            LocalDate.of(2026, 9, 30),
            RecurringSchedule.nextDueDate(
                lastOccurrence = LocalDate.of(2026, 8, 31),
                cadence = RecurringCadence.MONTHLY,
                dueDayOfMonth = 99
            )
        )
    }
}
