package com.spendwise.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Gap filling and label parsing.
 *
 * The failure mode here is a chart that looks perfectly reasonable and is
 * wrong: without the empty days the axis closes up, so spending on the 1st and
 * the 30th draws as two adjacent bars and reads as two consecutive days.
 */
class TimeBucketsTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun monthRange(year: Int, month: Int) =
        AnalyticsPeriod(PeriodType.MONTH, LocalDate.of(year, month, 1)).range(zone)

    @Test
    fun `every day in the period gets a bucket`() {
        val filled = TimeBuckets.fill(
            range = monthRange(2026, 8),
            byDay = true,
            totals = mapOf("2026-08-01" to 500.0, "2026-08-31" to 900.0),
            zone = zone
        )

        assertEquals(31, filled.size)
        assertEquals("2026-08-01", filled.first().key)
        assertEquals("2026-08-31", filled.last().key)
    }

    @Test
    fun `days without spending come back as zero rather than absent`() {
        val filled = TimeBuckets.fill(
            range = monthRange(2026, 8),
            byDay = true,
            totals = mapOf("2026-08-01" to 500.0, "2026-08-31" to 900.0),
            zone = zone
        )

        assertEquals(500.0, filled.first().total, 0.0001)
        assertEquals(0.0, filled[1].total, 0.0001)
        assertEquals(0.0, filled[29].total, 0.0001)
        assertEquals(900.0, filled.last().total, 0.0001)
        assertEquals(1_400.0, filled.sumOf { it.total }, 0.0001)
    }

    /** Keys must match the DAO's strftime output exactly, or nothing lines up. */
    @Test
    fun `day keys are zero padded to match the sql bucket format`() {
        val filled = TimeBuckets.fill(
            range = AnalyticsPeriod(PeriodType.DAY, LocalDate.of(2026, 3, 7)).range(zone),
            byDay = true,
            totals = mapOf("2026-03-07" to 120.0),
            zone = zone
        )

        assertEquals(listOf("2026-03-07"), filled.map { it.key })
        assertEquals(120.0, filled.single().total, 0.0001)
    }

    @Test
    fun `a year fills twelve month buckets`() {
        val filled = TimeBuckets.fill(
            range = AnalyticsPeriod(PeriodType.YEAR, LocalDate.of(2026, 5, 1)).range(zone),
            byDay = false,
            totals = mapOf("2026-01" to 100.0, "2026-12" to 300.0),
            zone = zone
        )

        assertEquals(12, filled.size)
        assertEquals("2026-01", filled.first().key)
        assertEquals("2026-12", filled.last().key)
        assertEquals(0.0, filled[5].total, 0.0001)
    }

    /** A range spanning a year boundary must not restart the month sequence. */
    @Test
    fun `month buckets run across a year boundary`() {
        val start = LocalDate.of(2025, 11, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 3, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

        val filled = TimeBuckets.fill(DateRange(start, end), byDay = false, totals = emptyMap(), zone = zone)

        assertEquals(
            listOf("2025-11", "2025-12", "2026-01", "2026-02"),
            filled.map { it.key }
        )
    }

    @Test
    fun `totals for buckets outside the range are ignored`() {
        val filled = TimeBuckets.fill(
            range = monthRange(2026, 8),
            byDay = true,
            totals = mapOf("2026-07-15" to 9_999.0, "2026-08-05" to 200.0),
            zone = zone
        )

        assertEquals(200.0, filled.sumOf { it.total }, 0.0001)
    }

    @Test
    fun `axis labels strip the leading zero from days`() {
        assertEquals("1", TimeBuckets.axisLabel("2026-08-01"))
        assertEquals("11", TimeBuckets.axisLabel("2026-08-11"))
    }

    /**
     * Asserted against the JDK's own month name rather than a literal, so the
     * test checks the substring lands on the month field without pinning the
     * suite to an English locale.
     */
    @Test
    fun `month labels read the month field`() {
        assertEquals(shortMonth(8), TimeBuckets.axisLabel("2026-08"))
        assertEquals(shortMonth(1), TimeBuckets.axisLabel("2026-01"))
        assertEquals(shortMonth(12), TimeBuckets.axisLabel("2026-12"))
    }

    @Test
    fun `full labels stand alone in a callout`() {
        assertEquals("11 ${shortMonth(8)}", TimeBuckets.fullLabel("2026-08-11"))
        assertEquals("${shortMonth(8)} 2026", TimeBuckets.fullLabel("2026-08"))
    }

    private fun shortMonth(month: Int): String =
        Month.of(month).getDisplayName(TextStyle.SHORT, Locale.getDefault())
}
