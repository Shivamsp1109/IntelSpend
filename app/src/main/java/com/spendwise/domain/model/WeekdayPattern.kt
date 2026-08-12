package com.spendwise.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * Spending on one day of the week, averaged over how often that day actually
 * occurred in the period.
 *
 * The average is the point. Comparing raw totals is unfair in a way that is
 * easy to miss: a month containing five Mondays and four Tuesdays makes Monday
 * look 25% heavier for no reason but the calendar, and comparing a weekend
 * total against a weekday total is worse still — roughly nine days against
 * twenty-two, so weekdays "win" every time regardless of behaviour.
 */
data class WeekdaySpend(
    val dayOfWeek: DayOfWeek,
    val total: Double,
    val occurrences: Int
) {
    val average: Double get() = if (occurrences == 0) 0.0 else total / occurrences

    val isWeekend: Boolean
        get() = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY
}

/** The seven days of the week in Monday-first order, with weekend comparison. */
data class WeekdayPattern(
    val days: List<WeekdaySpend>
) {
    val weekendAverage: Double get() = days.filter { it.isWeekend }.averageOfDays()

    val weekdayAverage: Double get() = days.filterNot { it.isWeekend }.averageOfDays()

    /** The heaviest day by average, or null when nothing was spent at all. */
    val busiestDay: WeekdaySpend?
        get() = days.filter { it.average > 0.0 }.maxByOrNull { it.average }

    /**
     * How much heavier an average weekend day is than an average weekday, as a
     * fraction. Null when there is no weekday spending to compare against —
     * dividing by zero would report infinite, and "up 0%" would be a claim the
     * data does not support.
     */
    val weekendUplift: Double?
        get() = if (weekdayAverage <= 0.0) null else (weekendAverage - weekdayAverage) / weekdayAverage

    /**
     * Averaged across the days themselves rather than by summing and dividing,
     * so a Saturday with five occurrences does not outweigh a Sunday with four.
     */
    private fun List<WeekdaySpend>.averageOfDays(): Double {
        val counted = filter { it.occurrences > 0 }
        return if (counted.isEmpty()) 0.0 else counted.sumOf { it.average } / counted.size
    }

    companion object {
        /**
         * @param totalsByIndex keyed 0 (Sunday) to 6 (Saturday), matching SQLite's %w.
         */
        fun from(
            totalsByIndex: Map<Int, Double>,
            range: DateRange,
            zone: ZoneId = ZoneId.systemDefault()
        ): WeekdayPattern {
            val occurrences = weekdayOccurrences(range, zone)
            val days = MONDAY_FIRST.map { day ->
                val index = day.sqliteIndex
                WeekdaySpend(
                    dayOfWeek = day,
                    total = totalsByIndex[index] ?: 0.0,
                    occurrences = occurrences[index] ?: 0
                )
            }
            return WeekdayPattern(days)
        }

        /** How many times each weekday falls inside the range, keyed like SQLite's %w. */
        fun weekdayOccurrences(range: DateRange, zone: ZoneId = ZoneId.systemDefault()): Map<Int, Int> {
            val first = Instant.ofEpochMilli(range.start).atZone(zone).toLocalDate()
            val last = Instant.ofEpochMilli(range.end).atZone(zone).toLocalDate()
            if (last.isBefore(first)) return emptyMap()

            val counts = HashMap<Int, Int>(7)
            var day = first
            while (!day.isAfter(last)) {
                val index = day.dayOfWeek.sqliteIndex
                counts[index] = (counts[index] ?: 0) + 1
                day = day.plusDays(1)
            }
            return counts
        }

        private val MONDAY_FIRST = listOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
            DayOfWeek.SATURDAY,
            DayOfWeek.SUNDAY
        )

        /** java.time counts Monday as 1 and Sunday as 7; SQLite calls Sunday 0. */
        private val DayOfWeek.sqliteIndex: Int
            get() = if (this == DayOfWeek.SUNDAY) 0 else value
    }
}
