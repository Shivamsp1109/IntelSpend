package com.spendwise.di

import android.content.Context
import androidx.room.Room
import com.spendwise.data.local.ExpenseDao
import com.spendwise.data.local.GoalDao
import com.spendwise.data.local.IncomeDao
import com.spendwise.data.local.MIGRATION_1_2
import com.spendwise.data.local.MIGRATION_2_3
import com.spendwise.data.local.MIGRATION_3_4
import com.spendwise.data.local.MIGRATION_4_5
import com.spendwise.data.local.MIGRATION_5_6
import com.spendwise.data.local.RecurringEntryDao
import com.spendwise.data.local.AnalyticsDao
import com.spendwise.data.local.LearnedCategoryDao
import com.spendwise.data.local.SpendWiseDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SpendWiseDatabase =
        Room.databaseBuilder(context, SpendWiseDatabase::class.java, "spendwise.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()

    @Provides
    fun provideExpenseDao(database: SpendWiseDatabase): ExpenseDao = database.expenseDao()

    @Provides
    fun provideIncomeDao(database: SpendWiseDatabase): IncomeDao = database.incomeDao()

    @Provides
    fun provideGoalDao(database: SpendWiseDatabase): GoalDao = database.goalDao()

    @Provides
    fun provideRecurringEntryDao(database: SpendWiseDatabase): RecurringEntryDao =
        database.recurringEntryDao()

    @Provides
    fun provideLearnedCategoryDao(database: SpendWiseDatabase): LearnedCategoryDao =
        database.learnedCategoryDao()

    @Provides
    fun provideAnalyticsDao(database: SpendWiseDatabase): AnalyticsDao =
        database.analyticsDao()
}
