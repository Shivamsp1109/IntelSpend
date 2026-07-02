package com.spendwise.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.Income
import com.spendwise.domain.model.IncomeSource

@Entity(tableName = "incomes")
data class IncomeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val amount: Double,
    /** ISO 4217 code stored as TEXT. */
    val currency: String = Currency.INR.code,
    /**
     * Enum name of [IncomeSource], e.g. "SALARY", "DIVIDENDS",
     * "MISCELLANEOUS".
     */
    val source: String,
    /**
     * Free-text description supplied by the user when source == MISCELLANEOUS.
     * Also serves as an additional note for any income entry.
     */
    val note: String? = null,
    val date: Long,
    val isSynced: Boolean = false
)

fun IncomeEntity.toDomain(): Income = Income(
    id = id,
    title = title,
    amount = amount,
    currency = Currency.fromCode(currency),
    source = IncomeSource.fromName(source),
    note = note,
    date = date,
    isSynced = isSynced
)

fun Income.toEntity(): IncomeEntity = IncomeEntity(
    id = id,
    title = title,
    amount = amount,
    currency = currency.code,
    source = source.name,
    note = note,
    date = date,
    isSynced = isSynced
)
