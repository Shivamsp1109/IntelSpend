package com.spendwise.domain.model

data class Expense(
    val id: Int = 0,
    val title: String,
    val amount: Double,
    val category: ExpenseCategory,
    val date: Long,
    val isSynced: Boolean = false,
    /** Optional merchant / payee name (e.g. "Amazon", "Swiggy"). */
    val merchant: String? = null,
    /** Currency of the transaction; defaults to INR. */
    val currency: Currency = Currency.INR,
    /** How this expense was captured; defaults to MANUAL. */
    val source: ExpenseSource = ExpenseSource.MANUAL
)
