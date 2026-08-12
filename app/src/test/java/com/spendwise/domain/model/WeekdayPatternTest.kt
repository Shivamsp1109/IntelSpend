package com.spendwise.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * Weekday spending, averaged per occurrence.
 *
 * The bug being guarded against is subtle and entirely invisible on screen: a
 * month containing five Mondays and four Tuesdays hands Monday 25% more spending
 * for no reason but the calendar, and a weekend total compared against a weekday
 * total pits nine days against twenty-two.
 */
class WeekdayPatternTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun monthRange(year: Int, month: Int) =
        AnalyticsPeriod(PeriodType.MONTH, LocalDate.of(year, month, 1)).range(zone)

    private fun weekRange() =
        AnalyticsPeriod(PeriodType.WEEK, LocalDate.of(2026, 8, 12)).range(zone)

    @Test
    fun `occurrences across a month account for every day`() {
        val range = monthRange(2026, 8)
        val occurrences = WeekdayPattern.weekdayOccurrences(range, zone)

        assertEquals(31, occurrences.values.sum())
        assertEquals(7, occurrences.size)
        assertTrue("a 31-day month gives each weekday four or five turns",
            occurrences.values.all { it == 4 || it == 5 })
    }

    @Test
    fun `each weekday occurs once in a single week`() {
        val occurrences = WeekdayPattern.weekdayOccurrences(weekRange(), zone)

        assertEquals(7, occurrences.size)
        assertTrue(occurrences.values.all { it == 1 })
    }

    /**
     * The heart of it: spend exactly the same amount every single day of a
     * month, and the raw totals still differ by weekday. The averages must not.
     */
    @Test
    fun `equal daily spending averages equally despite unequal totals`() {
        val range = monthRange(2026, 8)
        val occurrences = WeekdayPattern.weekdayOccurrences(range, zone)
        val totals = occurrences.mapValues { (_, count) -> count * 100.0 }

        val pattern = WeekdayPattern.from(totals, range, zone)

        pattern.days.forEach {
            assertEquals("${it.dayOfWeek} must average 100", 100.0, it.average, 0.0001)
        }
        assertTrue(
            "the totals themselves must genuinely differ, or this proves nothing",
            pattern.days.map { it.total }.distinct().size > 1
        )
    }

    @Test
    fun `days are returned monday first`() {
        val pattern = WeekdayPattern.from(emptyMap(), weekRange(), zone)

        assertEquals(
            listOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
            ),
            pattern.days.map { it.dayOfWeek }
        )
    }

    /** Index 0 is Sunday and 6 is Saturday, matching SQLite's %w. */
    @Test
    fun `sqlite weekday indices land on the right days`() {
        val range = weekRange()
        val pattern = WeekdayPattern.from(mapOf(0 to 500.0, 6 to 300.0), range, zone)

        assertEquals(500.0, pattern.days.single { it.dayOfWeek == DayOfWeek.SUNDAY }.total, 0.0001)
        assertEquals(300.0, pattern.days.single { it.dayOfWeek == DayOfWeek.SATURDAY }.total, 0.0001)
        assertEquals(0.0, pattern.days.single { it.dayOfWeek == DayOfWeek.MONDAY }.total, 0.0001)
    }

    @Test
    fun `weekend uplift compares like with like`() {
        val range = monthRange(2026, 8)
        val occurrences = WeekdayPattern.weekdayOccurrences(range, zone)
        val totals = occurrences.mapValues { (index, count) ->
            count * if (index == 0 || index == 6) 200.0 else 100.0
        }

        val pattern = WeekdayPattern.from(totals, range, zone)

        assertEquals(200.0, pattern.weekendAverage, 0.0001)
        assertEquals(100.0, pattern.weekdayAverage, 0.0001)
        assertEquals(1.0, pattern.weekendUplift!!, 0.0001)
        assertTrue(pattern.busiestDay!!.isWeekend)
    }

    /** Dividing by no weekday spending would report an infinite rise. */
    @Test
    fun `uplift is null when there is no weekday spending to compare against`() {
        val range = monthRange(2026, 8)
        val pattern = WeekdayPattern.from(mapOf(0 to 900.0), range, zone)

        assertNull(pattern.weekendUplift)
    }

    @Test
    fun `busiest day is null when nothing was spent`() {
        assertNull(WeekdayPattern.from(emptyMap(), weekRange(), zone).busiestDay)
    }

    @Test
    fun `a day that never occurred averages zero rather than dividing by zero`() {
        // A three-day window covering Mon to Wed leaves the rest of the week unseen.
        val range = AnalyticsPeriod.custom(
            LocalDate.of(2026, 8, 10),
            LocalDate.of(2026, 8, 12),
            zone
        ).range(zone)

        val pattern = WeekdayPattern.from(mapOf(1 to 300.0), range, zone)
        val sunday = pattern.days.single { it.dayOfWeek == DayOfWeek.SUNDAY }

        assertEquals(0, sunday.occurrences)
        assertEquals(0.0, sunday.average, 0.0001)
        assertEquals(300.0, pattern.days.single { it.dayOfWeek == DayOfWeek.MONDAY }.average, 0.0001)
    }
}
