package com.spendwise.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The window every chart on the analytics screen reads from.
 *
 * Holding one period for the whole screen is the point. Previously each chart
 * chose its own span — six months for the monthly bars, all-time for the
 * category split — so three charts sitting side by side described three
 * different questions with no way for the reader to tell.
 *
 * All ranges are half-open in intent but stored inclusive-to-last-millisecond,
 * because the DAO uses SQL `BETWEEN`. A transaction recorded at 23:59:59.999 on
 * the final day belongs to the period.
 */
enum class PeriodType { DAY, WEEK, MONTH, YEAR, CUSTOM }

data class DateRange(val start: Long, val end: Long) {
    /** Whole days covered, counting both ends — used for pacing and averages. */
    val dayCount: Int
        get() = ((end - start) / MILLIS_PER_DAY).toInt() + 1

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

data class AnalyticsPeriod(
    val type: PeriodType,
    /** Any instant inside the period; the range is derived from it. */
    val anchor: LocalDate = LocalDate.now(),
    /** Only meaningful when [type] is CUSTOM. */
    val customRange: DateRange? = null
) {
    fun range(zone: ZoneId = ZoneId.systemDefault()): DateRange = when (type) {
        PeriodType.DAY -> dayRange(anchor, zone)
        PeriodType.WEEK -> rangeBetween(anchor.startOfWeek(), anchor.startOfWeek().plusDays(6), zone)
        PeriodType.MONTH -> rangeBetween(
            anchor.withDayOfMonth(1),
            YearMonth.from(anchor).atEndOfMonth(),
            zone
        )
        PeriodType.YEAR -> rangeBetween(
            anchor.withDayOfYear(1),
            anchor.withDayOfYear(anchor.lengthOfYear()),
            zone
        )
        PeriodType.CUSTOM -> customRange ?: dayRange(anchor, zone)
    }

    /**
     * The equivalent window immediately before this one, for "vs. last month"
     * comparisons. A bare total says nothing on its own — the delta is where
     * the insight is.
     *
     * Calendar periods step back by one calendar unit rather than by a fixed
     * number of days, so February compares against January in full rather than
     * against "the 28 days before February".
     */
    fun previous(zone: ZoneId = ZoneId.systemDefault()): DateRange = when (type) {
        PeriodType.DAY -> dayRange(anchor.minusDays(1), zone)
        PeriodType.WEEK -> copy(anchor = anchor.minusWeeks(1)).range(zone)
        PeriodType.MONTH -> copy(anchor = anchor.minusMonths(1)).range(zone)
        PeriodType.YEAR -> copy(anchor = anchor.minusYears(1)).range(zone)
        PeriodType.CUSTOM -> {
            // A custom span has no calendar unit to step back by, so shift it by
            // its own length and land immediately before the current window.
            val current = range(zone)
            val span = current.end - current.start
            DateRange(current.start - span - 1, current.start - 1)
        }
    }

    /**
     * The same window one step earlier or later, for paging through history.
     * Negative steps go back.
     */
    fun shifted(steps: Long, zone: ZoneId = ZoneId.systemDefault()): AnalyticsPeriod = when (type) {
        PeriodType.DAY -> copy(anchor = anchor.plusDays(steps))
        PeriodType.WEEK -> copy(anchor = anchor.plusWeeks(steps))
        PeriodType.MONTH -> copy(anchor = anchor.plusMonths(steps))
        PeriodType.YEAR -> copy(anchor = anchor.plusYears(steps))
        PeriodType.CUSTOM -> {
            val current = range(zone)
            val span = current.end - current.start + 1
            copy(customRange = DateRange(current.start + span * steps, current.end + span * steps))
        }
    }

    /**
     * A factual name for the window: 'August 2026', '10 – 16 Aug 2026', '2026'.
     *
     * Deliberately never relative. The screen may render today's date as
     * "Today", but a report saved to a file and opened next week must still say
     * which day it covers.
     */
    fun displayLabel(zone: ZoneId = ZoneId.systemDefault()): String {
        val window = range(zone)
        val first = Instant.ofEpochMilli(window.start).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(window.end).atZone(zone).toLocalDate()

        return when (type) {
            PeriodType.DAY -> first.format(DAY_LABEL)
            PeriodType.MONTH -> first.format(MONTH_LABEL)
            PeriodType.YEAR -> first.year.toString()
            // Repeating the month on both ends of a range inside one month is noise.
            else -> if (first.year == last.year && first.month == last.month) {
                "${first.dayOfMonth} – ${last.format(DAY_LABEL)}"
            } else {
                "${first.format(SHORT_DAY_LABEL)} – ${last.format(DAY_LABEL)}"
            }
        }
    }

    /** Whether the window has started at all — paging past it only shows empty charts. */
    fun startsInTheFuture(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) =
        range(zone).start > now

    /**
     * Whether daily buckets are meaningful, or the chart should group by month.
     *
     * A calendar year always groups by month. A custom range is bounded by what
     * a chart can actually show — pick three years and daily bars would be a
     * thousand slivers a pixel wide, which is noise rather than detail.
     */
    val bucketsByDay: Boolean
        get() = when (type) {
            PeriodType.YEAR -> false
            PeriodType.CUSTOM -> (customRange?.dayCount ?: 1) <= MAX_DAILY_BUCKETS
            else -> true
        }

    private fun dayRange(day: LocalDate, zone: ZoneId) = rangeBetween(day, day, zone)

    private fun rangeBetween(first: LocalDate, last: LocalDate, zone: ZoneId) = DateRange(
        start = first.atStartOfDay(zone).toInstant().toEpochMilli(),
        // End of the last day, not its start — otherwise the final day's
        // transactions fall outside the range.
        end = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    )

    /** Monday-first, matching Indian and European calendar convention. */
    private fun LocalDate.startOfWeek(): LocalDate =
        minusDays((dayOfWeek.value - 1).toLong())

    companion object {
        /** A quarter's worth of bars is about as many as a phone-width chart can carry. */
        const val MAX_DAILY_BUCKETS = 92

        private val DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
        private val SHORT_DAY_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
        private val MONTH_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

        fun thisMonth() = AnalyticsPeriod(PeriodType.MONTH)

        /**
         * A custom window covering both dates in full. Built here so the
         * end-of-day boundary is computed in exactly one place — getting it
         * wrong drops the last day's transactions without any visible error.
         */
        fun custom(
            first: LocalDate,
            last: LocalDate,
            zone: ZoneId = ZoneId.systemDefault()
        ): AnalyticsPeriod {
            val ordered = if (last.isBefore(first)) first else last
            return AnalyticsPeriod(
                type = PeriodType.CUSTOM,
                anchor = ordered,
                customRange = DateRange(
                    start = first.atStartOfDay(zone).toInstant().toEpochMilli(),
                    end = ordered.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                )
            )
        }
    }
}
