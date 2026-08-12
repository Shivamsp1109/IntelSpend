package com.spendwise.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Turns the sparse rows SQL returns into a continuous series.
 *
 * `GROUP BY` only yields buckets that contain rows, so a day with no spending
 * is simply absent from the result. Plotting that directly is misleading in a
 * specific way: the gaps close up, so a month with spending on the 1st and the
 * 30th draws as two adjacent bars and reads as two consecutive days. The axis
 * has to carry the empty days for the shape of the series to mean anything.
 *
 * Key formats mirror the DAO's `strftime` output exactly — 'yyyy-MM-dd' for
 * days and 'yyyy-MM' for months — because the fill looks totals up by key.
 * That is why the parsing back to labels lives here too, next to the format it
 * depends on.
 */
object TimeBuckets {

    private val DAY_KEY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val MONTH_KEY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

    fun fill(
        range: DateRange,
        byDay: Boolean,
        totals: Map<String, Double>,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<TimeBucket> {
        val first = range.start.toLocalDate(zone)
        val last = range.end.toLocalDate(zone)
        if (last.isBefore(first)) return emptyList()

        return if (byDay) {
            generateSequence(first) { it.plusDays(1) }
                .takeWhile { !it.isAfter(last) }
                .map { it.format(DAY_KEY) }
        } else {
            generateSequence(YearMonth.from(first)) { it.plusMonths(1) }
                .takeWhile { !it.isAfter(YearMonth.from(last)) }
                .map { it.format(MONTH_KEY) }
        }.map { key -> TimeBucket(key, totals[key] ?: 0.0) }.toList()
    }

    /** Short axis label: '2026-08-11' becomes '11', '2026-08' becomes 'Aug'. */
    fun axisLabel(key: String): String = when {
        key.length == DAY_KEY_LENGTH -> key.substring(8).trimStart('0').ifEmpty { "0" }
        key.length == MONTH_KEY_LENGTH -> monthName(key)
        else -> key
    }

    /** Enough to stand alone in a callout: '11 Aug' or 'Aug 2026'. */
    fun fullLabel(key: String): String = when {
        key.length == DAY_KEY_LENGTH -> "${axisLabel(key)} ${monthName(key)}"
        key.length == MONTH_KEY_LENGTH -> "${monthName(key)} ${key.take(4)}"
        else -> key
    }

    private fun monthName(key: String): String {
        val month = key.substring(5, 7).toIntOrNull() ?: return key
        return Month.of(month).getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }

    private fun Long.toLocalDate(zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

    private const val DAY_KEY_LENGTH = 10
    private const val MONTH_KEY_LENGTH = 7
}
