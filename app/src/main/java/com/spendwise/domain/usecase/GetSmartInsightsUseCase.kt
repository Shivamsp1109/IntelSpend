package com.spendwise.domain.usecase

import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.Insight
import com.spendwise.domain.model.InsightTone
import com.spendwise.domain.model.TimeBuckets
import com.spendwise.util.CurrencyFormatter
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns a period's figures into things worth saying about them.
 *
 * The bar for including an insight is that it should tell the reader something
 * they could not get by looking at the charts above it. "You spent ₹40,000" is
 * already on screen; "you are eleven days in and on course for ₹62,000" is not.
 * Every rule therefore has a threshold, and a period where nothing unusual
 * happened correctly produces nothing.
 */
class GetSmartInsightsUseCase @Inject constructor() {

    operator fun invoke(
        snapshot: AnalyticsSnapshot,
        now: Long = System.currentTimeMillis()
    ): List<Insight> {
        val candidates = buildList {
            addIfPresent(overspentIncome(snapshot))
            addIfPresent(pace(snapshot, now))
            addIfPresent(overallMovement(snapshot))
            addIfPresent(biggestMover(snapshot))
            addIfPresent(newCategory(snapshot))
            addIfPresent(stoppedCategory(snapshot))
            addIfPresent(outlierDay(snapshot))
            addIfPresent(merchantConcentration(snapshot))
            addIfPresent(smallAndFrequent(snapshot))
            addIfPresent(weekendPattern(snapshot))
            addIfPresent(healthySavings(snapshot))
        }

        return candidates
            .sortedWith(compareBy({ it.rank }, { -it.magnitude }))
            .take(MAX_INSIGHTS)
            .map { it.insight }
    }

    // ── Rules ─────────────────────────────────────────────────────────────────

    private fun overspentIncome(snapshot: AnalyticsSnapshot): Candidate? {
        val summary = snapshot.summary
        if (summary.totalIncome <= 0.0 || summary.net >= 0.0) return null
        val shortfall = abs(summary.net)
        return Candidate(
            rank = 0,
            magnitude = shortfall / summary.totalIncome,
            insight = Insight(
                title = "Spending outran income",
                description = "You spent ${money(shortfall, snapshot.currency)} more than came in " +
                    "this period.",
                tone = InsightTone.WARNING
            )
        )
    }

    /**
     * Only for a period still running. Projecting a finished month would just
     * restate its total, and projecting one that has not started yet would
     * divide by no elapsed days at all.
     */
    private fun pace(snapshot: AnalyticsSnapshot, now: Long): Candidate? {
        val range = snapshot.summary.range
        if (now < range.start || now > range.end) return null

        val elapsed = ((now - range.start) / MILLIS_PER_DAY).toInt() + 1
        if (elapsed < MIN_DAYS_FOR_PACE || elapsed >= range.dayCount) return null
        if (snapshot.summary.totalExpense <= 0.0) return null

        val perDay = snapshot.summary.totalExpense / elapsed
        val projected = perDay * range.dayCount
        val previous = snapshot.summary.previousExpense

        val comparison = when {
            previous <= 0.0 -> "."
            projected > previous -> ", which would be ${money(projected - previous, snapshot.currency)} " +
                "more than last period."
            else -> ", which would be ${money(previous - projected, snapshot.currency)} less than last period."
        }

        return Candidate(
            rank = if (previous > 0.0 && projected > previous) 0 else 1,
            magnitude = if (previous <= 0.0) 0.0 else abs(projected - previous) / previous,
            insight = Insight(
                title = "On course for ${money(projected, snapshot.currency)}",
                description = "$elapsed of ${range.dayCount} days in, averaging " +
                    "${money(perDay, snapshot.currency)} a day$comparison",
                tone = if (previous > 0.0 && projected > previous) InsightTone.WARNING else InsightTone.NEUTRAL
            )
        )
    }

    private fun overallMovement(snapshot: AnalyticsSnapshot): Candidate? {
        val change = snapshot.summary.expenseChangePercent ?: return null
        if (abs(change) < MIN_MOVEMENT) return null

        val rising = change > 0
        return Candidate(
            rank = 1,
            magnitude = abs(change),
            insight = Insight(
                title = if (rising) "Spending is up ${percent(change)}" else "Spending is down ${percent(change)}",
                description = "${money(abs(snapshot.summary.expenseChange), snapshot.currency)} " +
                    "${if (rising) "more" else "less"} than the period before.",
                tone = if (rising) InsightTone.WARNING else InsightTone.POSITIVE
            )
        )
    }

    private fun biggestMover(snapshot: AnalyticsSnapshot): Candidate? {
        val mover = snapshot.categoryComparisons
            .firstOrNull { !it.isNew && !it.isDropped && it.previous > 0.0 }
            ?: return null
        val change = mover.changePercent ?: return null
        if (abs(change) < MIN_MOVEMENT || abs(mover.change) < MIN_AMOUNT) return null

        val rising = mover.change > 0
        return Candidate(
            rank = 1,
            magnitude = abs(change),
            insight = Insight(
                title = "${mover.category.label} moved the most",
                description = "${if (rising) "Up" else "Down"} ${percent(change)} to " +
                    "${money(mover.current, snapshot.currency)}, from " +
                    "${money(mover.previous, snapshot.currency)}.",
                tone = if (rising) InsightTone.WARNING else InsightTone.POSITIVE
            )
        )
    }

    private fun newCategory(snapshot: AnalyticsSnapshot): Candidate? {
        val fresh = snapshot.categoryComparisons
            .filter { it.isNew && it.current >= MIN_AMOUNT }
            .maxByOrNull { it.current } ?: return null

        return Candidate(
            rank = 2,
            magnitude = fresh.current,
            insight = Insight(
                title = "New: ${fresh.category.label}",
                description = "${money(fresh.current, snapshot.currency)} spent on " +
                    "${fresh.category.label.lowercase()}, with nothing in the period before.",
                tone = InsightTone.NEUTRAL
            )
        )
    }

    private fun stoppedCategory(snapshot: AnalyticsSnapshot): Candidate? {
        val dropped = snapshot.categoryComparisons
            .filter { it.isDropped && it.previous >= MIN_AMOUNT }
            .maxByOrNull { it.previous } ?: return null

        return Candidate(
            rank = 2,
            magnitude = dropped.previous,
            insight = Insight(
                title = "Nothing on ${dropped.category.label.lowercase()} this time",
                description = "You spent ${money(dropped.previous, snapshot.currency)} on " +
                    "${dropped.category.label.lowercase()} in the period before.",
                tone = InsightTone.POSITIVE
            )
        )
    }

    /**
     * A day far above the usual one. Compared against the average of the days
     * that actually had spending, not of every day in the period — otherwise a
     * month of quiet weekends drags the baseline down and half the working days
     * look like outliers.
     */
    private fun outlierDay(snapshot: AnalyticsSnapshot): Candidate? {
        val active = snapshot.spendOverTime.filter { it.total > 0.0 }
        if (active.size < MIN_DAYS_FOR_OUTLIER) return null

        val peak = active.maxByOrNull { it.total } ?: return null
        val others = active.filter { it.key != peak.key }
        if (others.isEmpty()) return null

        val baseline = others.sumOf { it.total } / others.size
        if (baseline <= 0.0) return null
        val ratio = peak.total / baseline
        if (ratio < OUTLIER_RATIO) return null

        return Candidate(
            rank = 1,
            magnitude = ratio,
            insight = Insight(
                title = "${TimeBuckets.fullLabel(peak.key)} stands out",
                description = "${money(peak.total, snapshot.currency)} in one day — " +
                    "${formatMultiple(ratio)}× your usual ${money(baseline, snapshot.currency)}.",
                tone = InsightTone.NEUTRAL
            )
        )
    }

    private fun merchantConcentration(snapshot: AnalyticsSnapshot): Candidate? {
        val total = snapshot.summary.totalExpense
        if (total <= 0.0) return null
        val top = snapshot.topMerchants.firstOrNull() ?: return null
        val share = top.total / total
        if (share < CONCENTRATION_SHARE) return null

        return Candidate(
            rank = 2,
            magnitude = share,
            insight = Insight(
                title = "${percent(share)} of it went to ${top.name}",
                description = "${money(top.total, snapshot.currency)} across " +
                    "${top.transactionCount} ${if (top.transactionCount == 1) "transaction" else "transactions"}.",
                tone = InsightTone.NEUTRAL
            )
        )
    }

    /**
     * Small, frequent spending hides inside a category total — forty ₹200 orders
     * look identical to one ₹8,000 purchase on every chart above.
     */
    private fun smallAndFrequent(snapshot: AnalyticsSnapshot): Candidate? {
        val frequent = snapshot.topMerchants
            .filter { it.transactionCount >= MIN_FREQUENT_COUNT }
            .maxByOrNull { it.total } ?: return null

        return Candidate(
            rank = 2,
            magnitude = frequent.transactionCount.toDouble(),
            insight = Insight(
                title = "${frequent.transactionCount} visits to ${frequent.name}",
                description = "${money(frequent.averageTransaction, snapshot.currency)} a time, " +
                    "${money(frequent.total, snapshot.currency)} altogether.",
                tone = InsightTone.NEUTRAL
            )
        )
    }

    private fun weekendPattern(snapshot: AnalyticsSnapshot): Candidate? {
        val uplift = snapshot.weekdayPattern.weekendUplift ?: return null
        if (abs(uplift) < WEEKEND_UPLIFT) return null

        val heavier = uplift > 0
        return Candidate(
            rank = 2,
            magnitude = abs(uplift),
            insight = Insight(
                title = if (heavier) "Weekends cost you more" else "Weekends are your quiet days",
                description = "An average weekend day runs ${percent(uplift)} " +
                    "${if (heavier) "above" else "below"} an average weekday — " +
                    "${money(snapshot.weekdayPattern.weekendAverage, snapshot.currency)} against " +
                    "${money(snapshot.weekdayPattern.weekdayAverage, snapshot.currency)}.",
                tone = if (heavier) InsightTone.NEUTRAL else InsightTone.POSITIVE
            )
        )
    }

    private fun healthySavings(snapshot: AnalyticsSnapshot): Candidate? {
        val rate = snapshot.summary.savingsRate ?: return null
        if (rate < HEALTHY_SAVINGS_RATE) return null

        return Candidate(
            rank = 2,
            magnitude = rate,
            insight = Insight(
                title = "You kept ${percent(rate)} of what came in",
                description = "${money(snapshot.summary.net, snapshot.currency)} left over " +
                    "from ${money(snapshot.summary.totalIncome, snapshot.currency)}.",
                tone = InsightTone.POSITIVE
            )
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private data class Candidate(val insight: Insight, val rank: Int, val magnitude: Double)

    private fun MutableList<Candidate>.addIfPresent(candidate: Candidate?) {
        if (candidate != null) add(candidate)
    }

    private fun money(amount: Double, currency: Currency) =
        CurrencyFormatter.format(amount, currency)

    private fun percent(fraction: Double) = "${(abs(fraction) * 100).roundToInt()}%"

    /** '3.5×' reads better than '3×' when the gap is that wide, but '4×' beats '4.0×'. */
    private fun formatMultiple(ratio: Double): String {
        val rounded = (ratio * 10).roundToInt() / 10.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }

    private companion object {
        const val MAX_INSIGHTS = 5
        const val MILLIS_PER_DAY = 86_400_000L

        /** Below this a change is noise, not news. */
        const val MIN_MOVEMENT = 0.1

        /** Percentages on tiny sums are dramatic and meaningless. */
        const val MIN_AMOUNT = 100.0

        const val MIN_DAYS_FOR_PACE = 3
        const val MIN_DAYS_FOR_OUTLIER = 4
        const val OUTLIER_RATIO = 3.0
        const val CONCENTRATION_SHARE = 0.25
        const val MIN_FREQUENT_COUNT = 8
        const val WEEKEND_UPLIFT = 0.25
        const val HEALTHY_SAVINGS_RATE = 0.2
    }
}
