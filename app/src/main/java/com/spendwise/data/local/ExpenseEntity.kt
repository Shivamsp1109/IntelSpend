package com.spendwise.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.ExpenseSource

// Indexed on date: every analytics aggregate is range-scoped, and an unindexed
// range predicate full-scans the table on each one.
@Entity(tableName = "expenses", indices = [Index(value = ["date"])])
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val amount: Double,
    val category: String,
    val date: Long,
    val isSynced: Boolean = false,
    /** Nullable — added in schema v2; NULL means unknown/not captured. */
    val merchant: String? = null,
    /** ISO 4217 code stored as TEXT; defaults to INR for v1 rows. */
    val currency: String = Currency.INR.code,
    /** Capture channel; defaults to MANUAL for v1 rows. */
    val source: String = ExpenseSource.MANUAL.name
)

fun ExpenseEntity.toDomain(): Expense = Expense(
    id = id,
    title = title,
    amount = amount,
    category = ExpenseCategory.fromLabel(category),
    date = date,
    isSynced = isSynced,
    merchant = merchant,
    currency = Currency.fromCode(currency),
    source = ExpenseSource.fromLabel(source)
)

fun Expense.toEntity(): ExpenseEntity = ExpenseEntity(
    id = id,
    title = title,
    amount = amount,
    category = category.label,
    date = date,
    isSynced = isSynced,
    merchant = merchant,
    currency = currency.code,
    source = source.name
)
