package com.spendwise.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * When a commitment falls due, and what it costs per month.
 *
 * Both calculations look trivial and are not. Adding thirty days to project the
 * next monthly payment drifts a day earlier every other month and lands rent in
 * the wrong month by the end of a year. Adding a weekly ₹1,000 to a yearly
 * ₹12,000 and calling it ₹13,000 of monthly obligation is wrong by a factor of
 * four. Both would produce numbers that look reasonable and quietly misinform.
 */
object RecurringSchedule {

    /**
     * The average months per period, used to express any cadence as a monthly
     * figure.
     *
     * Averaged deliberately. A weekly payment does not occur exactly four times a
     * month — it occurs 52 times a year, which is 4.348 times in an average month,
     * and using four understates a weekly commitment by 8%.
     */
    fun monthlyEquivalent(amount: Double, cadence: RecurringCadence): Double = when (cadence) {
        RecurringCadence.DAILY -> amount * DAYS_PER_YEAR / MONTHS_PER_YEAR
        RecurringCadence.WEEKLY -> amount * WEEKS_PER_YEAR / MONTHS_PER_YEAR
        RecurringCadence.BIWEEKLY -> amount * (WEEKS_PER_YEAR / 2) / MONTHS_PER_YEAR
        RecurringCadence.MONTHLY -> amount
        RecurringCadence.QUARTERLY -> amount / 3
        RecurringCadence.YEARLY -> amount / MONTHS_PER_YEAR
    }

    /**
     * Projects the next payment date from the last one.
     *
     * Calendar arithmetic, not day arithmetic. `plusMonths` knows that a payment
     * on 31 January next falls on 28 February, and that adding a month to that
     * does not then get stuck on the 28th — which is why [dueDayOfMonth] is
     * carried separately and re-applied each time. Without it, one short month
     * permanently walks a monthly commitment backwards through the calendar.
     */
    fun nextDueDate(
        lastOccurrence: LocalDate,
        cadence: RecurringCadence,
        dueDayOfMonth: Int? = null
    ): LocalDate = when (cadence) {
        RecurringCadence.DAILY -> lastOccurrence.plusDays(1)
        RecurringCadence.WEEKLY -> lastOccurrence.plusWeeks(1)
        RecurringCadence.BIWEEKLY -> lastOccurrence.plusWeeks(2)
        RecurringCadence.MONTHLY -> lastOccurrence.plusMonths(1).onDueDay(dueDayOfMonth ?: lastOccurrence.dayOfMonth)
        RecurringCadence.QUARTERLY -> lastOccurrence.plusMonths(3).onDueDay(dueDayOfMonth ?: lastOccurrence.dayOfMonth)
        RecurringCadence.YEARLY -> lastOccurrence.plusYears(1).onDueDay(dueDayOfMonth ?: lastOccurrence.dayOfMonth)
    }

    /**
     * Clamped to the month's length, so a commitment anchored to the 31st lands
     * on the last day of a shorter month rather than throwing or rolling into the
     * next one.
     */
    private fun LocalDate.onDueDay(day: Int): LocalDate =
        withDayOfMonth(day.coerceIn(1, lengthOfMonth()))

    /**
     * The nominal length of one period in days.
     *
     * Shared by detection (fitting gaps to a cadence) and matching (deciding
     * whether a payment falls in the expected window), so the two cannot drift
     * apart and start disagreeing about what "monthly" means.
     */
    fun periodDays(cadence: RecurringCadence): Double = when (cadence) {
        RecurringCadence.DAILY -> 1.0
        RecurringCadence.WEEKLY -> 7.0
        RecurringCadence.BIWEEKLY -> 14.0
        RecurringCadence.MONTHLY -> DAYS_PER_YEAR / MONTHS_PER_YEAR
        RecurringCadence.QUARTERLY -> DAYS_PER_YEAR / 4
        RecurringCadence.YEARLY -> DAYS_PER_YEAR
    }

    /** Epoch-millis convenience, since that is what the database stores. */
    fun nextDueDateMillis(
        lastOccurrence: Long,
        cadence: RecurringCadence,
        dueDayOfMonth: Int? = null,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long = nextDueDate(lastOccurrence.toLocalDate(zone), cadence, dueDayOfMonth)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

    fun Long.toLocalDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

    private const val DAYS_PER_YEAR = 365.2425
    private const val WEEKS_PER_YEAR = 52.1775
    private const val MONTHS_PER_YEAR = 12
}

/** What this commitment costs in an average month, whatever its cadence. */
val RecurringEntry.monthlyEquivalent: Double
    get() = RecurringSchedule.monthlyEquivalent(amount, cadence)
