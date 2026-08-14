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
    val merchant: String,
    val cadence: RecurringCadence,
    val type: RecurringType,
    val nature: TransactionNature,
    val category: ExpenseCategory,
    val currency: Currency,
    val averageAmount: Double,
    val occurrences: List<RecurringOccurrence>,
    val confidence: Double
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
        get() = signatureOf(merchant, currency, nature, category)

    companion object {
        private val ANCHORED_CADENCES = setOf(
            RecurringCadence.MONTHLY,
            RecurringCadence.QUARTERLY,
            RecurringCadence.YEARLY
        )

        /** Always clamped down to the real length of the target month. */
        private const val LAST_POSSIBLE_DAY = 31

        fun signatureOf(
            merchant: String,
            currency: Currency,
            nature: TransactionNature,
            category: ExpenseCategory
        ): String = listOf(
            merchant.trim().lowercase(),
            currency.code,
            nature.name,
            category.name
        ).joinToString("|")
    }
}

data class RecurringOccurrence(
    val expenseId: Int,
    val date: Long,
    val amount: Double
)
