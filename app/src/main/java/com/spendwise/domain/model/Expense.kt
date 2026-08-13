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
    val source: ExpenseSource = ExpenseSource.MANUAL,
    /**
     * Bank or UPI reference (RRN/UTR) when the source carried one.
     *
     * Identifies the same payment across documents: a UPI screenshot and the
     * statement that later lists it both quote this, while their merchant names
     * and timestamps often disagree.
     */
    val reference: String? = null,
    /** True when the date was substituted at import rather than read. */
    val dateIsAssumed: Boolean = false,
    /**
     * Whether this was money spent or money moved.
     *
     * Statement imports pick up transfers, card payments and ATM withdrawals
     * alongside real purchases; only [TransactionNature.Spending] counts towards
     * what was spent.
     */
    val nature: TransactionNature = TransactionNature.Spending
)
