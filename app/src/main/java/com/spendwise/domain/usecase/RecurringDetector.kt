package com.spendwise.domain.usecase

import com.spendwise.data.ingestion.duplicate.MerchantSimilarity
import com.spendwise.data.ingestion.normalizer.MerchantNormalizer
import com.spendwise.data.local.DismissedRecurringCandidateEntity
import com.spendwise.data.local.ExpenseTimeSeriesRow
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.RecurringCadence
import com.spendwise.domain.model.RecurringCandidate
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.model.RecurringOccurrence
import com.spendwise.domain.model.RecurringType
import com.spendwise.domain.model.TransactionNature
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds repeating payments in transaction history.
 *
 * Entirely deterministic and on-device. Working out that a payment repeats is
 * arithmetic over dates and amounts, not a judgement call, so there is nothing
 * here for a model to do that rules do not do better — and rules can be
 * explained to the user, cost nothing per import, and send no data anywhere.
 *
 * Kept as a pure function over lists so it can be tested against fixtures
 * without a database, a clock or a coroutine.
 */
object RecurringDetector {

    /**
     * Two payments this close together, for the same amount, are one payment.
     *
     * A card declined and retried, or a UPI mandate that failed and re-presented,
     * writes two rows for one charge. Left in, the pair reads as a one-day gap
     * and destroys the cadence fit for the whole series.
     */
    private const val RETRY_WINDOW_DAYS = 2L

    /**
     * Below this many identifying characters, a merchant name is only ever
     * matched exactly.
     *
     * Fuzzy matching is what lets `NETFLIX` and `Netflix India` be one payee. It
     * is also what would let two people called `Ravi K` and `Ravi S` become one,
     * and a personal transfer wrongly promoted into a monthly commitment is a
     * convincing, wrong number in someone's budget. Short names carry too little
     * signal to take that risk.
     *
     * Counted over letters and digits only. Measuring the raw string counts the
     * space in `Ravi K` towards the total, which is exactly the kind of name this
     * is meant to protect and exactly the character that carries no identity.
     */
    private const val MIN_FUZZY_MERCHANT_LENGTH = 6

    /** Above this, the amounts vary so much that this is shopping, not a commitment. */
    private const val MAX_AMOUNT_VARIATION = 0.6

    /** At or below this, the charge is the same every time: rent, an EMI, a fixed plan. */
    private const val FIXED_AMOUNT_VARIATION = 0.05

    /**
     * One missed interval is tolerated across the whole series.
     *
     * Real payments are missed: a card expires, a balance is short, a bank holiday
     * pushes a mandate past its window. Demanding an unbroken run would reject
     * genuine commitments for being ordinarily messy. Two or more gaps, though,
     * stops describing a schedule.
     */
    private const val MAX_SKIPPED_INTERVALS = 1

    /**
     * How stale a series may be before it is no longer worth suggesting.
     *
     * A subscription cancelled eight months ago is a real pattern in the history
     * and a useless thing to offer to track. Measured in cadence periods rather
     * than days, so a yearly premium is not judged by a monthly standard.
     */
    private const val MAX_STALE_PERIODS = 2.0

    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

    /**
     * A dismissal only holds while the pattern stays roughly what was rejected.
     *
     * Someone who dismisses an irregular trickle of payments to a shop should
     * still hear about it when that shop starts charging the same amount every
     * month — the thing they rejected is not the thing now happening.
     */
    private const val DISMISSAL_AMOUNT_TOLERANCE = 0.25

    fun detect(
        rows: List<ExpenseTimeSeriesRow>,
        existing: List<RecurringEntry>,
        dismissed: List<DismissedRecurringCandidateEntity>,
        now: Long
    ): List<RecurringCandidate> {
        if (rows.isEmpty()) return emptyList()

        return buildClusters(rows)
            .mapNotNull { cluster -> cluster.toCandidate(now) }
            .filterNot { candidate -> isAlreadyTracked(candidate, existing) }
            .filterNot { candidate -> isStillDismissed(candidate, dismissed) }
            .sortedByDescending { it.confidence }
    }

    // ── Clustering ────────────────────────────────────────────────────────────

    /**
     * Groups payments into things that might each be one commitment.
     *
     * The key is merchant *and* currency *and* nature *and* category, never
     * merchant alone. A shop can appear as both a purchase and a refund, and a
     * rupee series averaged together with a dollar one produces a number that is
     * neither.
     */
    private fun buildClusters(rows: List<ExpenseTimeSeriesRow>): List<Cluster> {
        val exact = rows
            .filter { it.merchant.isNotBlank() }
            .groupBy { row ->
                ClusterKey(
                    merchant = MerchantNormalizer.normalize(row.merchant),
                    currency = row.currency,
                    nature = row.nature,
                    category = row.category
                )
            }
            .map { (key, grouped) -> Cluster(key, dedupeRetries(grouped)) }

        // Fuzzy merging is only offered to groups that cannot stand up on their
        // own. Two groups that each already look like a commitment are left
        // alone: merging them would be combining two recognised payees on a
        // similarity score, and the cost of being wrong there is higher than the
        // cost of showing both.
        val (strong, weak) = exact.partition { it.occurrences.size >= RecurringCadence.MONTHLY.minimumOccurrences() }

        val leftovers = mutableListOf<Cluster>()
        for (fragment in weak) {
            val absorber = strong.firstOrNull { it.canAbsorb(fragment) }
            if (absorber != null) absorber.absorb(fragment) else leftovers += fragment
        }

        return strong + leftovers
    }

    /** Collapses a failed-and-retried charge into the single payment it was. */
    private fun dedupeRetries(rows: List<ExpenseTimeSeriesRow>): MutableList<RecurringOccurrence> {
        val sorted = rows.sortedBy { it.date }
        val kept = mutableListOf<RecurringOccurrence>()

        for (row in sorted) {
            val duplicate = kept.any { existing ->
                abs(existing.date - row.date) <= RETRY_WINDOW_DAYS * DAY_MILLIS &&
                    sameAmount(existing.amount, row.amount)
            }
            if (!duplicate) {
                kept += RecurringOccurrence(row.expenseId, row.date, row.amount)
            }
        }
        return kept
    }

    private fun sameAmount(first: Double, second: Double): Boolean {
        val larger = maxOf(abs(first), abs(second))
        if (larger == 0.0) return true
        return abs(first - second) / larger <= 0.01
    }

    // ── Cadence fitting ───────────────────────────────────────────────────────

    private fun Cluster.toCandidate(now: Long): RecurringCandidate? {
        val dates = occurrences.map { it.date }.sorted()
        if (dates.size < 2) return null

        val amounts = occurrences.map { it.amount }
        val mean = amounts.average()
        if (mean <= 0.0) return null

        val variation = coefficientOfVariation(amounts, mean)
        if (variation > MAX_AMOUNT_VARIATION) return null

        val fit = RecurringCadence.entries
            .filter { it.isDetectable() && dates.size >= it.minimumOccurrences() }
            .mapNotNull { cadence -> fitCadence(cadence, dates) }
            .filter { it.isFresh(dates.last(), now) }
            .maxByOrNull { it.regularity - it.skips * 0.1 }
            ?: return null

        return RecurringCandidate(
            merchant = key.merchant,
            cadence = fit.cadence,
            type = if (variation <= FIXED_AMOUNT_VARIATION) RecurringType.FIXED else RecurringType.VARIABLE,
            nature = TransactionNature.fromName(key.nature),
            category = ExpenseCategory.fromLabel(key.category),
            currency = Currency.fromCode(key.currency),
            averageAmount = mean,
            occurrences = occurrences.sortedBy { it.date },
            confidence = confidenceFor(fit, variation, dates.size)
        )
    }

    /**
     * Checks whether every gap in the series is a whole number of this cadence's
     * periods, within tolerance.
     *
     * Day gaps rather than calendar arithmetic, because for detection they are
     * the more forgiving of the two. A monthly bill produces gaps of 28 to 31
     * days as month lengths change, and one paid on the next working day after a
     * weekend drifts further still — a window absorbs all of that, where a
     * strict same-day-each-month rule would reject it.
     */
    private fun fitCadence(cadence: RecurringCadence, dates: List<Long>): CadenceFit? {
        val expected = cadence.expectedDays()
        val tolerance = cadence.toleranceDays()

        var skips = 0
        var totalError = 0.0

        for (index in 0 until dates.size - 1) {
            val gapDays = (dates[index + 1] - dates[index]).toDouble() / DAY_MILLIS
            val periods = (gapDays / expected).roundToInt()
            if (periods < 1 || periods > 1 + MAX_SKIPPED_INTERVALS) return null

            val deviation = abs(gapDays - periods * expected)
            val allowed = tolerance * periods
            if (deviation > allowed) return null

            skips += periods - 1
            totalError += deviation / allowed
        }

        if (skips > MAX_SKIPPED_INTERVALS) return null

        val gaps = dates.size - 1
        return CadenceFit(
            cadence = cadence,
            skips = skips,
            regularity = (1.0 - totalError / gaps).coerceIn(0.0, 1.0)
        )
    }

    private fun CadenceFit.isFresh(lastOccurrence: Long, now: Long): Boolean {
        val ageDays = (now - lastOccurrence).toDouble() / DAY_MILLIS
        return ageDays <= cadence.expectedDays() * MAX_STALE_PERIODS
    }

    // ── Confidence ────────────────────────────────────────────────────────────

    /**
     * How much the app is willing to claim for a detection.
     *
     * Three things move it: how evenly spaced the payments are, how alike the
     * amounts are, and how many there are. The caps matter more than the formula
     * — two annual premiums two years apart can look immaculate and still be a
     * coincidence, and the score has to say so rather than reporting near
     * certainty from two data points.
     */
    private fun confidenceFor(fit: CadenceFit, variation: Double, occurrences: Int): Double {
        val amountConsistency = (1.0 - variation.coerceIn(0.0, 1.0))
        val extra = occurrences - fit.cadence.minimumOccurrences()
        val volume = (0.5 + 0.25 * extra).coerceIn(0.0, 1.0)

        val base = 0.45 * fit.regularity + 0.30 * amountConsistency + 0.25 * volume
        val penalised = base - 0.05 * fit.skips

        return penalised.coerceIn(0.0, fit.cadence.confidenceCeiling(occurrences))
    }

    // ── Exclusions ────────────────────────────────────────────────────────────

    private fun isAlreadyTracked(candidate: RecurringCandidate, existing: List<RecurringEntry>): Boolean =
        existing.any { entry ->
            entry.currency == candidate.currency &&
                entry.nature == candidate.nature &&
                entry.category == candidate.category &&
                (entry.title.equals(candidate.merchant, ignoreCase = true) ||
                    MerchantSimilarity.sameMerchant(entry.title, candidate.merchant))
        }

    private fun isStillDismissed(
        candidate: RecurringCandidate,
        dismissed: List<DismissedRecurringCandidateEntity>
    ): Boolean {
        val match = dismissed.firstOrNull { it.signature == candidate.signature } ?: return false

        // The user rejected a specific pattern, not the merchant forever. A
        // different cadence, or a materially different amount, is a different
        // claim and deserves to be put to them again.
        if (match.cadence != candidate.cadence.name) return false

        val reference = abs(match.lastSeenAmount)
        if (reference == 0.0) return true
        val drift = abs(candidate.averageAmount - match.lastSeenAmount) / reference
        return drift <= DISMISSAL_AMOUNT_TOLERANCE
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Characters that actually identify a payee: spaces and punctuation do not. */
    private fun identifyingLength(merchant: String): Int =
        merchant.count { it.isLetterOrDigit() }

    private fun coefficientOfVariation(amounts: List<Double>, mean: Double): Double {
        if (amounts.size < 2) return 0.0
        val variance = amounts.sumOf { (it - mean) * (it - mean) } / amounts.size
        return sqrt(variance) / mean
    }

    private data class ClusterKey(
        val merchant: String,
        val currency: String,
        val nature: String,
        val category: String
    )

    private class Cluster(
        val key: ClusterKey,
        val occurrences: MutableList<RecurringOccurrence>
    ) {
        fun canAbsorb(other: Cluster): Boolean =
            key.currency == other.key.currency &&
                key.nature == other.key.nature &&
                key.category == other.key.category &&
                identifyingLength(key.merchant) >= MIN_FUZZY_MERCHANT_LENGTH &&
                identifyingLength(other.key.merchant) >= MIN_FUZZY_MERCHANT_LENGTH &&
                MerchantSimilarity.sameMerchant(key.merchant, other.key.merchant)

        fun absorb(other: Cluster) {
            occurrences += other.occurrences
            occurrences.sortBy { it.date }
        }
    }

    private data class CadenceFit(
        val cadence: RecurringCadence,
        val skips: Int,
        val regularity: Double
    )
}

/**
 * Daily is deliberately not detectable.
 *
 * A daily pattern is a habit, not a commitment — someone's morning coffee fits
 * it perfectly and belongs nowhere near a list of fixed obligations. Nothing a
 * household budgets as a recurring payment bills daily.
 */
private fun RecurringCadence.isDetectable(): Boolean = this != RecurringCadence.DAILY

private fun RecurringCadence.expectedDays(): Double = when (this) {
    RecurringCadence.DAILY -> 1.0
    RecurringCadence.WEEKLY -> 7.0
    RecurringCadence.BIWEEKLY -> 14.0
    RecurringCadence.MONTHLY -> 30.44
    RecurringCadence.QUARTERLY -> 91.31
    RecurringCadence.YEARLY -> 365.25
}

/** Wide enough for month lengths, weekends and a late working day. */
private fun RecurringCadence.toleranceDays(): Double = when (this) {
    RecurringCadence.DAILY -> 0.5
    RecurringCadence.WEEKLY -> 2.0
    RecurringCadence.BIWEEKLY -> 3.0
    RecurringCadence.MONTHLY -> 4.5
    RecurringCadence.QUARTERLY -> 10.0
    RecurringCadence.YEARLY -> 20.0
}

/**
 * How many payments are needed before a pattern is worth proposing.
 *
 * Three for the short cadences, where a third payment is the one that turns a
 * coincidence into a schedule. Long cadences get two, because insisting on three
 * would mean saying nothing about a yearly premium for two years — the tradeoff
 * is paid for with a much lower confidence ceiling instead.
 */
private fun RecurringCadence.minimumOccurrences(): Int = when (this) {
    RecurringCadence.QUARTERLY, RecurringCadence.YEARLY -> 2
    else -> 3
}

/** Two points can look perfect and still be chance; the ceiling says so. */
private fun RecurringCadence.confidenceCeiling(occurrences: Int): Double = when {
    this == RecurringCadence.YEARLY -> 0.5
    this == RecurringCadence.QUARTERLY && occurrences < 3 -> 0.6
    else -> 1.0
}
