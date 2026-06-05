package com.spendwise.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.ExpenseCategory

@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val amount: Double,
    val category: String,
    val date: Long,
    val isSynced: Boolean = false
)

fun ExpenseEntity.toDomain(): Expense = Expense(
    id = id,
    title = title,
    amount = amount,
    category = ExpenseCategory.fromLabel(category),
    date = date,
    isSynced = isSynced
)

fun Expense.toEntity(): ExpenseEntity = ExpenseEntity(
    id = id,
    title = title,
    amount = amount,
    category = category.label,
    date = date,
    isSynced = isSynced
)
