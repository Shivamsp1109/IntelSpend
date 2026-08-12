package com.spendwise.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Range arithmetic for the analytics period.
 *
 * Worth pinning because the failure mode is silent: an off-by-one at a boundary
 * doesn't crash, it just quietly drops the last day's transactions out of every
 * total on the screen. A fixed zone keeps the assertions stable wherever the
 * suite runs.
 */
class AnalyticsPeriodTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun millis(date: LocalDate, hour: Int = 12) =
        date.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()

    @Test
    fun `month range covers the first and last day in full`() {
        val period = AnalyticsPeriod(PeriodType.MONTH, LocalDate.of(2026, 8, 15))
        val range = period.range(zone)

        assertTrue("1 Aug must be inside", millis(LocalDate.of(2026, 8, 1), hour = 0) >= range.start)
        assertTrue("31 Aug must be inside", millis(LocalDate.of(2026, 8, 31), hour = 23) <= range.end)
        assertTrue("31 Jul must be outside", millis(LocalDate.of(2026, 7, 31)) < range.start)
        assertTrue("1 Sep must be outside", millis(LocalDate.of(2026, 9, 1)) > range.end)
    }

    /** The whole point of the inclusive end — a late transaction still counts. */
    @Test
    fun `a transaction at the last millisecond of the period is included`() {
        val period = AnalyticsPeriod(PeriodType.MONTH, LocalDate.of(2026, 8, 15))
        val range = period.range(zone)
        val lastInstant = LocalDate.of(2026, 9, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

        assertEquals(lastInstant, range.end)
    }

    @Test
    fun `day count is inclusive of both ends`() {
        assertEquals(31, AnalyticsPeriod(PeriodType.MONTH, LocalDate.of(2026, 8, 15)).range(zone).dayCount)
        assertEquals(28, AnalyticsPeriod(PeriodType.MONTH, LocalDate.of(2026, 2, 10)).range(zone).dayCount)
        assertEquals(29, AnalyticsPeriod(PeriodType.MONTH, LocalDate.of(2024, 2, 10)).range(zone).dayCount)
        assertEquals(7, AnalyticsPeriod(PeriodType.WEEK, LocalDate.of(2026, 8, 12)).range(zone).dayCount)
        assertEquals(1, AnalyticsPeriod(PeriodType.DAY, LocalDate.of(2026, 8, 12)).range(zone).dayCount)
        assertEquals(365, AnalyticsPeriod(PeriodType.YEAR, LocalDate.of(2026, 5, 1)).range(zone).dayCount)
    }

    @Test
    fun `week starts on monday`() {
        // 12 Aug 2026 is a Wednesday; the week must start on Monday the 10th.
        val range = AnalyticsPeriod(PeriodType.WEEK, LocalDate.of(2026, 8, 12)).range(zone)
        val monday = LocalDate.of(2026, 8, 10).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(monday, range.start)
    }

    /**
     * Calendar periods step back a calendar unit, not a fixed day count — so a
     * 28-day February compares against the whole of January.
     */
    @Test
    fun `previous month is the full prior calendar month`() {
        val period = AnalyticsPeriod(PeriodType.MONTH, LocalDate.of(2026, 3, 15))
        val previous = period.previous(zone)

        assertEquals(28, previous.dayCount)
        assertTrue(millis(LocalDate.of(2026, 2, 1), hour = 0) >= previous.start)
        assertTrue(millis(LocalDate.of(2026, 2, 28), hour = 23) <= previous.end)
    }

    @Test
    fun `previous period never overlaps the current one`() {
        for (type in listOf(PeriodType.DAY, PeriodType.WEEK, PeriodType.MONTH, PeriodType.YEAR)) {
            val period = AnalyticsPeriod(type, LocalDate.of(2026, 8, 12))
            val current = period.range(zone)
            val previous = period.previous(zone)
            assertTrue("$type previous must end before current starts", previous.end < current.start)
        }
    }

    @Test
    fun `custom range shifts back by its own length`() {
        val start = LocalDate.of(2026, 8, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 8, 11).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val period = AnalyticsPeriod(PeriodType.CUSTOM, customRange = DateRange(start, end))

        val current = period.range(zone)
        val previous = period.previous(zone)

        assertEquals(10, current.dayCount)
        assertEquals(10, previous.dayCount)
        assertTrue(previous.end < current.start)
    }

    @Test
    fun `year buckets by month while shorter periods bucket by day`() {
        assertTrue(AnalyticsPeriod(PeriodType.DAY).bucketsByDay)
        assertTrue(AnalyticsPeriod(PeriodType.WEEK).bucketsByDay)
        assertTrue(AnalyticsPeriod(PeriodType.MONTH).bucketsByDay)
        assertTrue(!AnalyticsPeriod(PeriodType.YEAR).bucketsByDay)
    }

    /** Daily bars over a multi-year custom range would be a thousand slivers. */
    @Test
    fun `a long custom range falls back to monthly buckets`() {
        val short = AnalyticsPeriod.custom(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 3, 1),
            zone
        )
        val long = AnalyticsPeriod.custom(
            LocalDate.of(2023, 1, 1),
            LocalDate.of(2026, 1, 1),
            zone
        )

        assertTrue(short.bucketsByDay)
        assertTrue(!long.bucketsByDay)
    }

    @Test
    fun `custom covers both endpoint days in full`() {
        val period = AnalyticsPeriod.custom(
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 10),
            zone
        )
        val range = period.range(zone)

        assertEquals(10, range.dayCount)
        assertTrue("1 Aug must be inside", millis(LocalDate.of(2026, 8, 1), hour = 0) >= range.start)
        assertTrue("10 Aug must be inside", millis(LocalDate.of(2026, 8, 10), hour = 23) <= range.end)
        assertTrue("11 Aug must be outside", millis(LocalDate.of(2026, 8, 11)) > range.end)
    }

    @Test
    fun `shifting back one step lands on the previous period`() {
        for (type in listOf(PeriodType.DAY, PeriodType.WEEK, PeriodType.MONTH, PeriodType.YEAR)) {
            val period = AnalyticsPeriod(type, LocalDate.of(2026, 8, 12))
            assertEquals(
                "$type shifted back must equal previous()",
                period.previous(zone),
                period.shifted(-1, zone).range(zone)
            )
        }
    }

    @Test
    fun `shifting a custom range keeps its length and does not overlap`() {
        val period = AnalyticsPeriod.custom(
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 10),
            zone
        )
        val back = period.shifted(-1, zone).range(zone)

        assertEquals(10, back.dayCount)
        assertTrue(back.end < period.range(zone).start)
    }

    /** Paging forward past today only ever shows an empty chart. */
    @Test
    fun `a period starting after now is flagged as future`() {
        val now = LocalDate.of(2026, 8, 12).atStartOfDay(zone).toInstant().toEpochMilli()
        val thisMonth = AnalyticsPeriod(PeriodType.MONTH, LocalDate.of(2026, 8, 12))

        assertTrue(!thisMonth.startsInTheFuture(now, zone))
        assertTrue(thisMonth.shifted(1, zone).startsInTheFuture(now, zone))
    }
}
