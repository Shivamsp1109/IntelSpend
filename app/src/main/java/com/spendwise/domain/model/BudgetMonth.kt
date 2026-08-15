package com.spendwise.domain.model

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * The calendar month a budget is measured over.
 *
 * Its own type because the boundaries have to agree in three places — the query
 * that sums spending, the guard that stops an alert repeating, and the label the
 * screen shows — and three separate calculations of "this month" is three
 * chances for them to disagree by a day and report a budget as blown that is
 * not.
 *
 * Local, not UTC. A payment made on the evening of the 31st belongs to that
 * month in the user's own calendar, and a UTC boundary would push it into the
 * next one for anyone east of Greenwich.
 */
data class BudgetMonth(
    val start: Long,
    val endExclusive: Long,
    /** `yyyy-MM`, the form stored against an alert. */
    val key: String
) {
    /** Inclusive end, since the DAO uses BETWEEN. */
    val endInclusive: Long get() = endExclusive - 1

    companion object {
        fun containing(
            instant: Long = System.currentTimeMillis(),
            zone: ZoneId = ZoneId.systemDefault()
        ): BudgetMonth {
            val month = YearMonth.from(Instant.ofEpochMilli(instant).atZone(zone))
            return BudgetMonth(
                start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                endExclusive = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                key = month.toString()
            )
        }
    }
}
