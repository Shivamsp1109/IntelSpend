package com.spendwise.domain.model

/**
 * Domain model for a recurring charge (subscription, EMI, etc.).
 *
 * The many-to-many relationship with [Expense] is managed via
 * [RecurringExpenseCrossRef] in the data layer; this model stays
 * free of Room concerns.
 *
 * [nature] is the field that stops this being a flat list of outgoings. Rent, a
 * car loan EMI and a monthly mutual-fund contribution are all "₹15,000 every
 * month", but one is consumption, one repays a debt and one buys an asset the
 * user still owns. Summing them together would describe none of them.
 */
data class RecurringEntry(
    val id: Int = 0,
    val title: String,
    val amount: Double,
    val cadence: RecurringCadence,
    val type: RecurringType,
    val currency: Currency = Currency.INR,
    val nature: TransactionNature = TransactionNature.Spending,
    val category: ExpenseCategory = ExpenseCategory.Other,
    val source: RecurringSource = RecurringSource.MANUAL,
    /** How many historical payments this was inferred from; 0 when hand-entered. */
    val occurrenceCount: Int = 0,
    /**
     * How sure the detector was, in [0, 1]. Always 1.0 for a manual entry: the
     * user asserting a commitment exists is not a guess.
     */
    val confidence: Double = 1.0,
    val status: RecurringStatus = RecurringStatus.ACTIVE,
    /** When the most recent payment for this went out, if one is known. */
    val lastOccurrenceDate: Long? = null,
    /** When the next one is expected, projected from [lastOccurrenceDate]. */
    val nextDueDate: Long? = null,
    /**
     * The day of the month this is anchored to, for monthly and longer cadences.
     *
     * Carried separately rather than re-read from the last payment each time. A
     * commitment due on the 31st is paid on the 28th in February, and projecting
     * the next date from *that* would leave it stuck on the 28th for good —
     * one short month would permanently walk it backwards through the calendar.
     */
    val dueDayOfMonth: Int? = null,
    /**
     * A price seen on a recent payment that disagrees with [amount], waiting on
     * the user.
     *
     * Deliberately not applied on its own. [amount] is a figure they agreed to
     * and it decides what the app says they owe each month; a charge that
     * disagrees is a question, not a correction to make for them.
     */
    val pendingAmount: Double? = null,
    /** A change already refused, so the same question is not asked every month. */
    val declinedAmount: Double? = null
) {
    /** Whether this should be counted in commitments and reminded about now. */
    val isLive: Boolean get() = status.isLive

    /** Whether there is a price change waiting for an answer. */
    val hasPendingPriceChange: Boolean get() = pendingAmount != null
}
