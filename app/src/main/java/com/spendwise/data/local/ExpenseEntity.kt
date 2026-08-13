package com.spendwise.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.ExpenseSource
import com.spendwise.domain.model.TransactionNature

// Indexed on date: every analytics aggregate is range-scoped, and an unindexed
// range predicate full-scans the table on each one. Indexed on reference too,
// because duplicate detection looks rows up by it on every import.
// dateIsAssumed is indexed because duplicate detection queries for exactly these
// rows, and they are rare — which is precisely when an index pays off.
@Entity(
    tableName = "expenses",
    indices = [
        Index(value = ["date"]),
        Index(value = ["reference"]),
        Index(value = ["dateIsAssumed"]),
        Index(value = ["nature"])
    ]
)
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
    val source: String = ExpenseSource.MANUAL.name,
    /**
     * The bank or UPI reference for this payment (RRN/UTR), when the source
     * carried one.
     *
     * The only field that identifies the same payment across two different
     * documents: a UPI screenshot and the bank statement that later lists it
     * both quote this number, while their merchant names and timestamps often
     * disagree. NULL for manual entries and anything that did not expose one.
     */
    val reference: String? = null,
    /**
     * True when [date] was substituted rather than read off the document.
     *
     * Stored rather than derived because it cannot be recovered later: a date
     * assumed at import time is indistinguishable from a real one afterwards,
     * and duplicate detection needs to know not to trust it.
     */
    val dateIsAssumed: Boolean = false,
    /**
     * Enum name of [com.spendwise.domain.model.TransactionNature].
     *
     * Indexed because every analytics aggregate filters on it — a transfer must
     * not be counted as spending, so the filter is on the hot path rather than
     * an occasional query.
     */
    val nature: String = TransactionNature.Spending.name
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
    source = ExpenseSource.fromLabel(source),
    reference = reference,
    dateIsAssumed = dateIsAssumed,
    nature = TransactionNature.fromName(nature)
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
    source = source.name,
    reference = reference,
    dateIsAssumed = dateIsAssumed,
    nature = nature.name
)
