package com.spendwise.domain.model

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
     * A stable identity for this candidate, so a dismissal can be matched back to
     * it on a later scan. Merchant alone is not enough: the same payee can appear
     * as both a subscription and a one-off refund.
     */
    val signature: String
        get() = signatureOf(merchant, currency, nature, category)

    companion object {
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
