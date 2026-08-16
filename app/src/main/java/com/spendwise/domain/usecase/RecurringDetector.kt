package com.spendwise.domain.usecase

import com.spendwise.data.ingestion.duplicate.MerchantSimilarity
import com.spendwise.data.ingestion.normalizer.MerchantNormalizer
import com.spendwise.data.local.DismissedRecurringCandidateEntity
import com.spendwise.data.local.ExpenseTimeSeriesRow
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.MatchBasis
import com.spendwise.domain.model.RecurringCadence
import com.spendwise.domain.model.RecurringCandidate
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.model.RecurringOccurrence
import com.spendwise.domain.model.RecurringSchedule
import com.spendwise.domain.model.RecurringType
import com.spendwise.domain.model.TransactionNature
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Finds repeating payments in transaction history.
 *
 * Entirely deterministic and on-device. Working out that a payment repeats is
 * arithmetic over dates and amounts, not a judgement call, so there is nothing
 * here for a model to do that rules do not do better — and rules can be
 * explained to the user, cost nothing per import, and send no data anywhere.
 *
 * Two passes, because the same commitment is not always written down the same
 * way. The first groups by payee, which is how most repetition presents itself.
 * The second catches what the first cannot: the same sum leaving on the same
 * rhythm under names that never agree, which is what a loan looks like when it
 * is typed in by hand one month and read off a bank statement the next.
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

    /**
     * The most an amount-anchored detection is ever allowed to claim.
     *
     * These are held to a lower ceiling than a named match on purpose: identical
     * sums on a schedule are strong evidence but not proof, and the user is the
     * only one who can say whether two differently-named payments are the same
     * commitment.
     */
    private const val AMOUNT_ANCHORED_CEILING = 0.65

    /**
     * How many distinct payees a sum may be shared by before it stops being one
     * commitment written inconsistently and starts being a coincidence.
     *
     * Two: the manual entry and the imported statement row for the same charge.
     * Three or more separate people paid the same round figure is what people's
     * spending looks like, not what a commitment looks like.
     */
    private const val MAX_ANCHORED_PAYEES = 2

    /**
     * How far two figures at one payee may be apart and still be the same
     * commitment.
     *
     * Generous, because this only distinguishes separate commitments from each
     * other; a price that moved within one commitment is a different question,
     * asked and answered separately.
     */
    private const val SAME_COMMITMENT_AMOUNT_TOLERANCE = 0.5

    fun detect(
        rows: List<ExpenseTimeSeriesRow>,
        existing: List<RecurringEntry>,
        dismissed: List<DismissedRecurringCandidateEntity>,
        now: Long
    ): List<RecurringCandidate> {
        if (rows.isEmpty()) return emptyList()

        val clusters = buildNameClusters(rows)
        val found = mutableListOf<RecurringCandidate>()
        val claimed = mutableSetOf<Int>()

        // Everything paid to one payee, taken as a whole. The ordinary case: a
        // subscription with a merchant that does nothing else.
        for (cluster in clusters) {
            val candidate = cluster.toCandidate(now, MatchBasis.NAME) ?: continue
            found += candidate
            claimed += cluster.rows.map { it.expenseId }
        }

        // One repeating charge hiding among a payee's other spending.
        //
        // A merchant you both subscribe to and shop at — Amazon, Google, Apple —
        // produces one cluster holding a ₹299 monthly subscription and thirty
        // unrelated purchases. Taken as a whole it fits no cadence and its
        // amounts vary hugely, so the whole cluster was rejected and the
        // subscription inside it went with it. Splitting by the exact sum finds
        // the charge that actually repeats.
        for (cluster in clusters) {
            if (cluster.rows.any { it.expenseId in claimed }) continue
            for (subset in cluster.splitByExactAmount()) {
                // A firmer floor than usual. One payee's spending throws up
                // coincidental repeats of the same figure far more readily than
                // two separate payees do, so a long cadence backed by only two
                // payments is not enough evidence here.
                val candidate = subset.toCandidate(now, MatchBasis.NAME, minOccurrences = 3) ?: continue
                found += candidate
                claimed += subset.rows.map { it.expenseId }
            }
        }

        // The same sum on the same rhythm under names that never agree.
        for (cluster in buildAmountClusters(rows.filterNot { it.expenseId in claimed })) {
            val candidate = cluster.toCandidate(now, MatchBasis.AMOUNT, minOccurrences = 3) ?: continue
            found += candidate
            claimed += cluster.rows.map { it.expenseId }
        }

        return found
            .filterNot { candidate -> isAlreadyTracked(candidate, existing) }
            .filterNot { candidate -> isStillDismissed(candidate, dismissed) }
            .sortedByDescending { it.confidence }
    }

    // ── Clustering by payee ───────────────────────────────────────────────────

    /**
     * Groups payments by who they went to.
     *
     * The key is payee *and* currency *and* nature, never payee alone. A rupee
     * series averaged together with a dollar one produces a number that is
     * neither, and a shop can appear as both a purchase and a refund.
     *
     * Category is deliberately not part of it. It is a label the user picks and
     * can change at will: filing a bill under Utilities one month and Bills the
     * next does not make it a different bill, and keying on it split single
     * commitments in two whenever the model and the user disagreed.
     */
    private fun buildNameClusters(rows: List<ExpenseTimeSeriesRow>): List<Cluster> {
        val exact = rows
            .filter { it.merchant.isNotBlank() }
            .groupBy { row ->
                ClusterKey(
                    merchant = MerchantNormalizer.normalize(row.merchant),
                    currency = row.currency,
                    nature = row.nature
                )
            }
            .map { (key, grouped) -> Cluster(identity = key.merchant, rows = grouped.toMutableList()) }

        // Fuzzy merging is only offered to groups that cannot stand up on their
        // own. Two groups that each already look like a commitment are left
        // alone: merging them would be combining two recognised payees on a
        // similarity score, and the cost of being wrong there is higher than the
        // cost of showing both.
        val (strong, weak) = exact.partition {
            it.rows.size >= RecurringCadence.MONTHLY.minimumOccurrences()
        }

        val leftovers = mutableListOf<Cluster>()
        for (fragment in weak) {
            val absorber = strong.firstOrNull { it.canAbsorb(fragment) }
            if (absorber != null) absorber.absorb(fragment) else leftovers += fragment
        }

        return strong + leftovers
    }

    // ── Clustering by amount ──────────────────────────────────────────────────

    /**
     * Groups leftover payments by the exact sum that left the account.
     *
     * This is the case a hand-entered commitment creates. Someone records an EMI
     * in July with whatever title and category makes sense to them, and in
     * August the same debit arrives inside a bank statement with the lender's
     * registered name, a narration full of reference codes, and a category the
     * model chose. Nothing about the two records agrees except the two things
     * that actually define the commitment: the amount, and the month.
     *
     * The amount must match to the paisa. Anything looser starts collecting
     * unrelated purchases that happen to cost about the same, and the whole
     * value of this pass is that identical sums arriving on a schedule are hard
     * to produce by chance.
     *
     * Strictly limited to a handful of payees, and that limit is what keeps it
     * honest. The case this serves is one commitment written down two ways, so
     * two names is the most it can legitimately need. Without the limit it
     * pooled payments to genuinely different people who happened to be paid the
     * same round figure a month or so apart, and presented them as a commitment
     * under whichever name was most recent — reporting a one-off ₹3,000 to a
     * friend as a monthly obligation.
     *
     * The cadence fit helps too. Two different ₹500 subscriptions both billing
     * monthly have two payments a month between them and fit no cadence at all,
     * so the pair is rejected rather than merged.
     */
    private fun buildAmountClusters(rows: List<ExpenseTimeSeriesRow>): List<Cluster> =
        rows
            .filter { it.merchant.isNotBlank() }
            .groupBy { row -> AmountKey(row.currency, (row.amount * 100).roundToLong()) }
            .values
            .filter { it.size >= RecurringCadence.MONTHLY.minimumOccurrences() }
            .filter { grouped ->
                grouped
                    .map { MerchantNormalizer.normalize(it.merchant).lowercase() }
                    .distinct()
                    .size <= MAX_ANCHORED_PAYEES
            }
            .map { grouped ->
                val first = grouped.first()
                Cluster(
                    // The sum itself, because the names are precisely what does
                    // not agree here. A label-derived identity would move every
                    // time a new statement worded the payee differently, and a
                    // dismissal recorded against the old wording would stop
                    // matching — so a rejected suggestion would reappear.
                    identity = "amount:${first.currency}:${(first.amount * 100).roundToLong()}",
                    rows = grouped.toMutableList()
                )
            }

    /** Collapses a failed-and-retried charge into the single payment it was. */
    private fun dedupeRetries(rows: List<ExpenseTimeSeriesRow>): List<RecurringOccurrence> {
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

    private fun Cluster.toCandidate(
        now: Long,
        basis: MatchBasis,
        minOccurrences: Int? = null
    ): RecurringCandidate? {
        val occurrences = dedupeRetries(rows)
        val dates = occurrences.map { it.date }.sorted()
        if (dates.size < 2) return null
        if (minOccurrences != null && dates.size < minOccurrences) return null

        val amounts = occurrences.map { it.amount }
        val mean = amounts.average()
        if (mean <= 0.0) return null

        val variation = coefficientOfVariation(amounts, mean)
        if (variation > MAX_AMOUNT_VARIATION) return null

        val fit = RecurringCadence.entries
            .filter {
                it.isDetectable() &&
                    dates.size >= maxOf(it.minimumOccurrences(), minOccurrences ?: 0)
            }
            .mapNotNull { cadence -> fitCadence(cadence, dates) }
            .filter { it.isFresh(dates.last(), now) }
            .maxByOrNull { it.regularity - it.skips * 0.1 }
            ?: return null

        val ceiling = when (basis) {
            MatchBasis.AMOUNT -> minOf(AMOUNT_ANCHORED_CEILING, fit.cadence.confidenceCeiling(dates.size))
            MatchBasis.NAME -> fit.cadence.confidenceCeiling(dates.size)
        }

        val charge = currentCharge(occurrences, mean)

        return RecurringCandidate(
            // The most recent wording, so the user sees the acronyms and
            // capitalisation they typed rather than a title-cased rewrite of it.
            merchant = rows.maxBy { it.date }.merchant,
            identity = identity,
            cadence = fit.cadence,
            type = charge.type,
            nature = dominant { it.nature }.let(TransactionNature::fromName),
            category = dominant { it.category }.let(ExpenseCategory::fromLabel),
            currency = Currency.fromCode(rows.first().currency),
            amount = charge.amount,
            previousAmount = charge.previousAmount,
            occurrences = occurrences.sortedBy { it.date },
            confidence = confidenceFor(fit, variation, dates.size, ceiling),
            basis = basis
        )
    }

    /**
     * What this commitment charges now, read from the end of the series rather
     * than averaged across it.
     *
     * Prices change. A subscription that went from ₹199 to ₹139 averages to
     * ₹169 — a figure that was never charged and never will be, which would
     * nonetheless be added to the user's monthly obligations and used to decide
     * what they can afford. What matters is the price in force.
     *
     * The run of most recent payments that agree with each other is what settles
     * it. Two in a row at the same figure is a price; one is not yet, and a
     * series where no two consecutive payments agree is a genuinely variable
     * bill — an electricity account — where the average really is the most
     * useful thing to report.
     */
    private fun currentCharge(occurrences: List<RecurringOccurrence>, mean: Double): Charge {
        val newestFirst = occurrences.sortedByDescending { it.date }
        val latest = newestFirst.first().amount
        val run = newestFirst.takeWhile { sameAmount(it.amount, latest) }

        if (run.size < 2) {
            // No settled price: the amount moves every time, so the average over
            // the series is the honest summary of what it costs.
            return Charge(amount = mean, previousAmount = null, type = RecurringType.VARIABLE)
        }

        // Whatever was being charged before the current price took effect. Null
        // when the price has never changed.
        val previous = newestFirst.drop(run.size).firstOrNull()?.amount

        return Charge(
            amount = latest,
            previousAmount = previous?.takeUnless { sameAmount(it, latest) },
            type = RecurringType.FIXED
        )
    }

    private data class Charge(
        val amount: Double,
        val previousAmount: Double?,
        val type: RecurringType
    )

    /**
     * The value most of these payments carry.
     *
     * Needed because nature and category are no longer part of the cluster key,
     * so one cluster can hold several. The majority is the honest answer: three
     * months filed as a loan repayment and one misfiled as spending is a loan.
     */
    private fun Cluster.dominant(select: (ExpenseTimeSeriesRow) -> String): String =
        rows.groupingBy(select).eachCount().maxBy { it.value }.key

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
     * amounts are, and how many there are. The ceilings matter more than the
     * formula — two annual premiums two years apart can look immaculate and still
     * be a coincidence, and the score has to say so rather than reporting near
     * certainty from two data points.
     */
    private fun confidenceFor(
        fit: CadenceFit,
        variation: Double,
        occurrences: Int,
        ceiling: Double
    ): Double {
        val amountConsistency = (1.0 - variation.coerceIn(0.0, 1.0))
        val extra = occurrences - fit.cadence.minimumOccurrences()
        val volume = (0.5 + 0.25 * extra).coerceIn(0.0, 1.0)

        val base = 0.45 * fit.regularity + 0.30 * amountConsistency + 0.25 * volume
        val penalised = base - 0.05 * fit.skips

        return penalised.coerceIn(0.0, ceiling)
    }

    // ── Exclusions ────────────────────────────────────────────────────────────

    /**
     * Whether this is a commitment the user is already tracking.
     *
     * A backstop rather than the main defence — payments belonging to a tracked
     * commitment are excluded from the series before detection ever sees them.
     * This catches the window between a payment arriving and reconciliation
     * attributing it.
     *
     * The amount is part of the comparison, and has to be. One payee can hold
     * several separate commitments — a ₹59 charge on the 18th and a ₹299 one on
     * the 28th are two subscriptions, not one — and matching on the name alone
     * meant accepting the first made the app treat every other charge from that
     * payee as already handled, so they silently vanished from the list.
     *
     * A price the user has been asked about counts as the same commitment too.
     * Otherwise a subscription that went up would come back as a brand new
     * suggestion alongside the question about its own price change.
     */
    private fun isAlreadyTracked(candidate: RecurringCandidate, existing: List<RecurringEntry>): Boolean =
        existing.any { entry ->
            entry.currency == candidate.currency &&
                entry.nature == candidate.nature &&
                (entry.title.equals(candidate.merchant, ignoreCase = true) ||
                    MerchantSimilarity.sameMerchant(entry.title, candidate.merchant)) &&
                entry.chargesAbout(candidate.amount)
        }

    /**
     * Whether a figure is recognisably this commitment's own charge.
     *
     * Wide, because it only has to tell one commitment from another at the same
     * payee rather than police small movements — a price rise is a separate
     * question with its own answer. Narrow enough that ₹59 and ₹299 are never
     * confused for each other.
     */
    private fun RecurringEntry.chargesAbout(other: Double): Boolean =
        listOfNotNull(amount, pendingAmount, declinedAmount).any { known ->
            val larger = maxOf(abs(known), abs(other))
            larger == 0.0 || abs(known - other) / larger <= SAME_COMMITMENT_AMOUNT_TOLERANCE
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
        val drift = abs(candidate.amount - match.lastSeenAmount) / reference
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
        val nature: String
    )

    /** Currency plus the exact sum in the smallest unit, so no rounding creeps in. */
    private data class AmountKey(val currency: String, val paise: Long)

    private class Cluster(
        val identity: String,
        val rows: MutableList<ExpenseTimeSeriesRow>
    ) {
        fun canAbsorb(other: Cluster): Boolean =
            rows.first().currency == other.rows.first().currency &&
                rows.first().nature == other.rows.first().nature &&
                identifyingLength(identity) >= MIN_FUZZY_MERCHANT_LENGTH &&
                identifyingLength(other.identity) >= MIN_FUZZY_MERCHANT_LENGTH &&
                MerchantSimilarity.sameMerchant(identity, other.identity)

        fun absorb(other: Cluster) {
            rows += other.rows
            rows.sortBy { it.date }
        }

        /**
         * This payee's spending, split into the exact sums that recur.
         *
         * The identity carries the amount, so a payee with two separate
         * subscriptions yields two commitments rather than one that overwrites
         * the other — and so a dismissal of one does not silence the other.
         */
        fun splitByExactAmount(): List<Cluster> = rows
            .groupBy { (it.amount * 100).roundToLong() }
            .filter { (_, group) -> group.size >= RecurringCadence.MONTHLY.minimumOccurrences() }
            .map { (paise, group) ->
                Cluster(identity = "$identity#$paise", rows = group.toMutableList())
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

/** Shared with matching, so the two cannot disagree about what "monthly" means. */
private fun RecurringCadence.expectedDays(): Double = RecurringSchedule.periodDays(this)

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
