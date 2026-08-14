package com.spendwise.domain.usecase

import com.spendwise.data.ingestion.duplicate.MerchantSimilarity
import com.spendwise.data.ingestion.normalizer.MerchantNormalizer
import com.spendwise.data.local.ExpenseTimeSeriesRow
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.model.RecurringSchedule
import com.spendwise.domain.model.RecurringType
import kotlin.math.abs

/**
 * Attributes payments that have already gone out to the commitments they belong
 * to.
 *
 * This is what stops a reminder saying rent is due in two days when it went out
 * last week. A commitment knows its schedule but not its payments, and nothing
 * connects the two at import time — statements arrive in bulk, out of order, and
 * often for months before the commitment was confirmed at all.
 *
 * Pure, and separated from the database for the same reason as the detector:
 * this is the part with judgement in it, and judgement should be testable
 * against fixtures rather than through Room.
 */
object RecurringMatcher {

    /**
     * How far either side of the expected date a payment still counts.
     *
     * A fraction of the period rather than a fixed number of days, so a weekly
     * commitment is not given a monthly commitment's slack. Bills get paid early,
     * late, on the next working day after a weekend, and on whatever day the
     * mandate happened to present — but a payment half a cycle out is more likely
     * to be next cycle's than this one's.
     */
    private const val WINDOW_FRACTION = 0.45

    /**
     * How far a payment may be from the expected amount, for a fixed commitment.
     *
     * Loose enough for a price rise or a part-month proration, tight enough that a
     * ₹3,000 purchase does not settle a ₹20,000 rent payment.
     */
    private const val FIXED_AMOUNT_TOLERANCE = 0.2

    /**
     * The same, for a commitment whose amount is expected to move.
     *
     * A utility bill genuinely doubles between seasons, so the amount carries
     * little signal here and the date window does most of the work. It is still
     * bounded: without a ceiling, any purchase at the merchant would qualify.
     */
    private const val VARIABLE_AMOUNT_TOLERANCE = 1.5

    /** Matching a short name loosely is how two different people become one. */
    private const val MIN_FUZZY_MERCHANT_LENGTH = 6

    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

    /**
     * Works out which unlinked payments belong to which live commitment.
     *
     * Only live ones: a paused or ended commitment should not quietly absorb
     * payments, because that would move its due date and bring it back to life
     * in every total that reads it.
     */
    fun match(
        entries: List<RecurringEntry>,
        unlinked: List<ExpenseTimeSeriesRow>
    ): List<RecurringMatch> {
        if (entries.isEmpty() || unlinked.isEmpty()) return emptyList()

        val claimed = mutableSetOf<Int>()

        return entries
            .filter { it.isLive }
            .mapNotNull { entry ->
                val payments = unlinked
                    .filter { it.expenseId !in claimed && belongsTo(entry, it) }
                    .sortedBy { it.date }

                if (payments.isEmpty()) return@mapNotNull null

                // One payment cannot settle two commitments. Without this, two
                // similar entries for the same merchant would both claim it and
                // both move their due dates on one charge.
                claimed += payments.map { it.expenseId }

                val latest = payments.maxOf { it.date }
                RecurringMatch(
                    entry = entry,
                    expenseIds = payments.map { it.expenseId },
                    lastOccurrenceDate = latest,
                    nextDueDate = RecurringSchedule.nextDueDateMillis(
                        lastOccurrence = latest,
                        cadence = entry.cadence,
                        dueDayOfMonth = entry.dueDayOfMonth
                    )
                )
            }
    }

    private fun belongsTo(entry: RecurringEntry, payment: ExpenseTimeSeriesRow): Boolean {
        if (entry.currency.code != payment.currency) return false
        if (entry.nature.name != payment.nature) return false
        if (!sameMerchant(entry.title, payment.merchant)) return false
        if (!plausibleAmount(entry, payment.amount)) return false
        return inExpectedWindow(entry, payment.date)
    }

    private fun sameMerchant(entryTitle: String, merchant: String): Boolean {
        val left = MerchantNormalizer.normalize(entryTitle)
        val right = MerchantNormalizer.normalize(merchant)
        if (left.equals(right, ignoreCase = true)) return true

        // Same guard as detection: below this, only an exact match will do.
        if (identifyingLength(left) < MIN_FUZZY_MERCHANT_LENGTH) return false
        if (identifyingLength(right) < MIN_FUZZY_MERCHANT_LENGTH) return false
        return MerchantSimilarity.sameMerchant(left, right)
    }

    private fun identifyingLength(merchant: String): Int = merchant.count { it.isLetterOrDigit() }

    private fun plausibleAmount(entry: RecurringEntry, amount: Double): Boolean {
        val expected = abs(entry.amount)
        if (expected == 0.0) return true

        val tolerance =
            if (entry.type == RecurringType.FIXED) FIXED_AMOUNT_TOLERANCE
            else VARIABLE_AMOUNT_TOLERANCE

        return abs(amount - expected) / expected <= tolerance
    }

    /**
     * Whether the payment falls near where the schedule said it would.
     *
     * With no due date projected yet there is nothing to compare against, so any
     * payment after the last known one is accepted — that is the case where the
     * commitment has just been created and is still learning its own rhythm.
     */
    private fun inExpectedWindow(entry: RecurringEntry, date: Long): Boolean {
        val expected = entry.nextDueDate
            ?: return entry.lastOccurrenceDate?.let { date > it } ?: true

        val windowMillis = (RecurringSchedule.periodDays(entry.cadence) * WINDOW_FRACTION * DAY_MILLIS).toLong()
        return abs(date - expected) <= windowMillis
    }
}

/** One commitment and the payments now attributed to it. */
data class RecurringMatch(
    val entry: RecurringEntry,
    val expenseIds: List<Int>,
    val lastOccurrenceDate: Long,
    val nextDueDate: Long
)
