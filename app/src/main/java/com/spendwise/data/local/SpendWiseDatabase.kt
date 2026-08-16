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
        ExpenseDeleteSyncEntity::class,
        IncomeDeleteSyncEntity::class,
        LearnedCategoryEntity::class,
        DismissedRecurringCandidateEntity::class,
        CategoryBudgetEntity::class
    ],
    version = 13,
    exportSchema = true
)
abstract class SpendWiseDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao
    abstract fun incomeDao(): IncomeDao
    abstract fun goalDao(): GoalDao
    abstract fun recurringEntryDao(): RecurringEntryDao
    abstract fun learnedCategoryDao(): LearnedCategoryDao
    abstract fun analyticsDao(): AnalyticsDao
    abstract fun categoryBudgetDao(): CategoryBudgetDao
}
