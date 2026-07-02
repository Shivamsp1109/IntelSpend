package com.spendwise.domain.model

/**
 * Domain model for a recurring charge (subscription, EMI, etc.).
 *
 * The many-to-many relationship with [Expense] is managed via
 * [RecurringExpenseCrossRef] in the data layer; this model stays
 * free of Room concerns.
 */
data class RecurringEntry(
    val id: Int = 0,
    val title: String,
    val amount: Double,
    val cadence: RecurringCadence,
    val type: RecurringType
)
