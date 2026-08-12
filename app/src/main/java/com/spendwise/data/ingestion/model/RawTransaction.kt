package com.spendwise.data.ingestion.model

import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.ExpenseSource

data class RawTransaction(
    val title: String,
    val amount: Double,
    val date: Long,
    val merchant: String? = null,
    val currency: Currency = Currency.INR,
    val category: ExpenseCategory = ExpenseCategory.Other,
    val type: TransactionType = TransactionType.DEBIT,
    val source: ExpenseSource = ExpenseSource.MANUAL,
    val notes: String? = null,
    var isDuplicate: Boolean = false,
    var isSelected: Boolean = true,
    val confidence: Float = 1.0f,
    val fieldConfidence: FieldConfidence = FieldConfidence()
)

enum class TransactionType { DEBIT, CREDIT }
