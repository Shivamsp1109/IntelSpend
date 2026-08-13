package com.spendwise.domain.model

/**
 * Domain model for an income entry.
 *
 * When [source] is [IncomeSource.MISCELLANEOUS], [note] should contain
 * the user's free-text description of the income.
 */
data class Income(
    val id: Int = 0,
    val title: String,
    val amount: Double,
    val currency: Currency = Currency.INR,
    val source: IncomeSource = IncomeSource.SALARY,
    /** Free-text note; required when source == MISCELLANEOUS. */
    val note: String? = null,
    val date: Long,
    val isSynced: Boolean = false,
    /** Bank or UPI reference, when the source carried one. Used to spot re-imports. */
    val reference: String? = null,
    /** True when the date was substituted at import rather than read. */
    val dateIsAssumed: Boolean = false
)
