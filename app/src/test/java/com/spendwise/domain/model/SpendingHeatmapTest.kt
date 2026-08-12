package com.spendwise.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Calendar grid alignment.
 *
 * A heatmap only means anything if each day sits under its own weekday column.
 * Chunking the days into sevens would look perfectly fine and be wrong for every
 * month that does not begin on a Monday — the columns would silently shift.
 */
class SpendingHeatmapTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun dailyBuckets(year: Int, month: Int): List<TimeBucket> {
        val range = AnalyticsPeriod(PeriodType.MONTH, LocalDate.of(year, month, 1)).range(zone)
        return TimeBuckets.fill(range, byDay = true, totals = emptyMap(), zone = zone)
    }

    @Test
    fun `every row holds exactly seven slots`() {
        val weeks = SpendingHeatmap.build(dailyBuckets(2026, 8))

        assertTrue(weeks.isNotEmpty())
        assertTrue(weeks.all { it.days.size == 7 })
    }

    @Test
    fun `no day is lost or invented`() {
        val weeks = SpendingHeatmap.build(dailyBuckets(2026, 8))
        val present = weeks.flatMap { it.days }.filterNotNull()

        assertEquals(31, present.size)
        assertEquals(LocalDate.of(2026, 8, 1), present.first().date)
        assertEquals(LocalDate.of(2026, 8, 31), present.last().date)
        assertEquals(present.sortedBy { it.date }, present)
    }

    /** The alignment property itself, checked across a full year of months. */
    @Test
    fun `the first day lands in its own weekday column every month`() {
        for (month in 1..12) {
            val weeks = SpendingHeatmap.build(dailyBuckets(2026, month))
            val firstRow = weeks.first().days
            val firstDay = firstRow.filterNotNull().first()

            assertEquals(
                "month $month must start under ${firstDay.date.dayOfWeek}",
                firstDay.date.dayOfWeek.value - 1,
                firstRow.indexOfFirst { it != null }
            )
            assertTrue(
                "slots before the 1st must be blank",
                firstRow.take(firstRow.indexOfFirst { it != null }).all { it == null }
            )
        }
    }

    @Test
    fun `the last row is padded out rather than left short`() {
        val weeks = SpendingHeatmap.build(dailyBuckets(2026, 8))
        val lastRow = weeks.last().days
        val lastDay = lastRow.filterNotNull().last()

        assertEquals(7, lastRow.size)
        assertEquals(lastDay.date.dayOfWeek.value - 1, lastRow.indexOfLast { it != null })
    }

    @Test
    fun `totals survive the layout`() {
        val range = AnalyticsPeriod(PeriodType.MONTH, LocalDate.of(2026, 8, 1)).range(zone)
        val buckets = TimeBuckets.fill(
            range,
            byDay = true,
            totals = mapOf("2026-08-03" to 450.0, "2026-08-20" to 1_200.0),
            zone = zone
        )

        val present = SpendingHeatmap.build(buckets).flatMap { it.days }.filterNotNull()

        assertEquals(1_650.0, present.sumOf { it.total }, 0.0001)
        assertEquals(450.0, present.single { it.date == LocalDate.of(2026, 8, 3) }.total, 0.0001)
    }

    @Test
    fun `no buckets produces no grid rather than an empty row`() {
        assertEquals(emptyList<HeatmapWeek>(), SpendingHeatmap.build(emptyList()))
    }

    /** Monthly keys are not dates; they must not be smuggled into a day grid. */
    @Test
    fun `month keys are ignored`() {
        val weeks = SpendingHeatmap.build(
            listOf(TimeBucket("2026-08", 500.0), TimeBucket("2026-08-04", 100.0))
        )
        val present = weeks.flatMap { it.days }.filterNotNull()

        assertEquals(1, present.size)
        assertEquals(LocalDate.of(2026, 8, 4), present.single().date)
    }
}
