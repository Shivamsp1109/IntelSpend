package com.spendwise.domain.model

import com.spendwise.domain.model.RecurringSchedule.toLocalDate

/**
 * A repeating payment the app believes it has found, before the user has said
 * whether it is real.
 *
 * Deliberately not a [RecurringEntry]. An entry is a commitment the user stands
 * behind; this is an inference, and keeping the two apart means nothing counts
 * towards a total or fires a reminder until somebody has agreed it should.
 *
 * [nature] and [category] are carried through from the transactions that
 * produced it rather than re-guessed, because they are what decides whether this
 * is rent, an EMI or a monthly investment — three things that must never be
 * summed as if they were the same.
 */
data class RecurringCandidate(
    /** What to show the user: their own wording, or the statement's. */
    val merchant: String,
    /**
     * A stable key for this pattern, separate from the label.
     *
     * The two must not be the same string. The label is whatever the most recent
     * record happened to say, and it moves — a payee written one way in June and
     * another in July would change identity between scans, and a dismissal
     * recorded against the old wording would stop matching, so a rejected
     * suggestion would come back. The identity is derived from what actually
     * grouped the payments and does not drift.
     */
    val identity: String,
    val cadence: RecurringCadence,
    val type: RecurringType,
    val nature: TransactionNature,
    val category: ExpenseCategory,
    val currency: Currency,
    val averageAmount: Double,
    val occurrences: List<RecurringOccurrence>,
    val confidence: Double,
    val basis: MatchBasis = MatchBasis.NAME
) {
    val occurrenceCount: Int get() = occurrences.size

    /** The most recent occurrence, which is what a due date is projected from. */
    val lastOccurrenceDate: Long get() = occurrences.maxOf { it.date }

    /**
     * The day of the month this commitment is anchored to, for cadences where
     * that means anything.
     *
     * Read from the payments rather than from the latest one alone, because the
     * latest one may be the distorted one: a commitment due on the 31st shows up
     * as the 28th every February, and anchoring to that would walk it backwards
     * through the calendar permanently.
     *
     * Payments that land on the last day of their month are treated as
     * month-end rather than as their literal date — 28 February, 30 April and
     * 31 May are the same instruction, and only the clamp in
     * [RecurringSchedule.nextDueDate] can express it.
     */
    val dueDayOfMonth: Int?
        get() {
            if (cadence !in ANCHORED_CADENCES) return null

            val days = occurrences.map { it.date.toLocalDate() }
            if (days.isEmpty()) return null

            val monthEnd = days.count { it.dayOfMonth == it.lengthOfMonth() }
            if (monthEnd * 2 > days.size) return LAST_POSSIBLE_DAY

            return days.groupingBy { it.dayOfMonth }
                .eachCount()
                .maxByOrNull { (day, count) -> count * 100 + day }
                ?.key
        }

    /**
     * A stable identity for this candidate, so a dismissal can be matched back to
     * it on a later scan. Merchant alone is not enough: the same payee can appear
     * as both a subscription and a one-off refund.
     */
    val signature: String
        get() = signatureOf(identity, currency, nature)

    companion object {
        private val ANCHORED_CADENCES = setOf(
            RecurringCadence.MONTHLY,
            RecurringCadence.QUARTERLY,
            RecurringCadence.YEARLY
        )

        /** Always clamped down to the real length of the target month. */
        private const val LAST_POSSIBLE_DAY = 31

        /**
         * Category is deliberately absent.
         *
         * It is a label the user picks and can change at will, not part of what
         * makes this the same commitment — filing a payment under Utilities one
         * month and Bills the next does not make it a different bill.
         */
        fun signatureOf(
            identity: String,
            currency: Currency,
            nature: TransactionNature
        ): String = listOf(
            identity.trim().lowercase(),
            currency.code,
            nature.name
        ).joinToString("|")
    }
}

data class RecurringOccurrence(
    val expenseId: Int,
    val date: Long,
    val amount: Double
)

/**
 * What tied these payments together.
 *
 * Worth surfacing, because the two deserve different scepticism. A run of
 * payments to the same payee is self-evidently one commitment. A run of
 * identical amounts on a schedule under *different* names probably is too — a
 * loan typed in by hand in July and read off a statement in August — but it
 * might be a coincidence, and the user is the only one who can tell.
 */
enum class MatchBasis {
    /** Same payee each time. */
    NAME,

    /** Same amount, same rhythm, names that do not agree. */
    AMOUNT
}
