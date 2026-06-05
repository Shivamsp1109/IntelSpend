package com.spendwise.domain.model

data class Expense(
    val id: Int = 0,
    val title: String,
    val amount: Double,
    val category: ExpenseCategory,
    val date: Long,
    val isSynced: Boolean = false
)
