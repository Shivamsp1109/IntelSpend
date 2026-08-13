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
    val confidence: Double = 1.0
)
