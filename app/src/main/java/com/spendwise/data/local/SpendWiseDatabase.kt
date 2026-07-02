package com.spendwise.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ExpenseEntity::class,
        IncomeEntity::class,
        GoalEntity::class,
        RecurringEntity::class,
        RecurringExpenseCrossRef::class,
        ExpenseDeleteSyncEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class SpendWiseDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun incomeDao(): IncomeDao
    abstract fun goalDao(): GoalDao
    abstract fun recurringEntryDao(): RecurringEntryDao
}
