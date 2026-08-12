package com.spendwise.domain.model

import java.time.DayOfWeek
import java.time.LocalDate

/** One cell of the heatmap. Null slots in a week are days outside the period. */
data class HeatmapDay(
    val date: LocalDate,
    val total: Double
)

data class HeatmapWeek(
    /** Exactly seven slots, Monday first; null where the day falls outside the period. */
    val days: List<HeatmapDay?>
)

/**
 * Lays daily totals out as a calendar.
 *
 * A calendar grid answers a question the trend chart cannot: *when* in the
 * rhythm of the week or month the heavy days fall. That only works if each day
 * sits under its own weekday column, so the grid is padded at both ends rather
 * than simply chunked into sevens — otherwise every month whose 1st is not a
 * Monday would silently shift the whole column alignment.
 */
object SpendingHeatmap {

    private const val DAYS_IN_WEEK = 7

    /** @param buckets daily buckets, keys formatted 'yyyy-MM-dd' as [TimeBuckets] produces. */
    fun build(buckets: List<TimeBucket>): List<HeatmapWeek> {
        val days = buckets.mapNotNull { bucket ->
            runCatching { LocalDate.parse(bucket.key) }.getOrNull()
                ?.let { HeatmapDay(it, bucket.total) }
        }.sortedBy { it.date }

        if (days.isEmpty()) return emptyList()

        val leadingBlanks = days.first().date.dayOfWeek.mondayFirstIndex
        val trailingBlanks = DAYS_IN_WEEK - 1 - days.last().date.dayOfWeek.mondayFirstIndex

        val slots = buildList<HeatmapDay?> {
            repeat(leadingBlanks) { add(null) }
            addAll(days)
            repeat(trailingBlanks) { add(null) }
        }

        return slots.chunked(DAYS_IN_WEEK).map { HeatmapWeek(it) }
    }

    /** Monday is 0 through Sunday 6, matching the app's Monday-first weeks. */
    private val DayOfWeek.mondayFirstIndex: Int get() = value - 1
}
