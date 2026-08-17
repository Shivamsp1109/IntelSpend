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
import com.spendwise.data.local.MIGRATION_6_7
import com.spendwise.data.local.MIGRATION_7_8
import com.spendwise.data.local.MIGRATION_8_9
import com.spendwise.data.local.MIGRATION_9_10
import com.spendwise.data.local.MIGRATION_10_11
import com.spendwise.data.local.MIGRATION_11_12
import com.spendwise.data.local.MIGRATION_12_13
import com.spendwise.data.local.MIGRATION_13_14
import com.spendwise.data.local.AssetDao
import com.spendwise.data.local.LoanDetailsDao
import com.spendwise.data.local.CategoryBudgetDao
import com.spendwise.data.local.RecurringEntryDao
import com.spendwise.data.local.AnalyticsDao
import com.spendwise.data.local.DatabaseEncryption
import com.spendwise.data.local.LearnedCategoryDao
import com.spendwise.data.local.SpendWiseDatabase
import com.spendwise.util.crypto.DatabasePassphrase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * The database, encrypted at rest with SQLCipher.
     *
     * Room drives SQLCipher through the same SupportSQLite interfaces it uses
     * for the platform database, so every DAO, query and migration is unchanged
     * — the only difference is what the bytes on disk look like to anyone who
     * gets hold of them.
     */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        databasePassphrase: DatabasePassphrase
    ): SpendWiseDatabase {
        System.loadLibrary("sqlcipher")

        val databaseFile = context.getDatabasePath(DATABASE_NAME)

        // Resolved first, because it reports whether the previous key survived.
        val passphrase = databasePassphrase.asText()

        if (databasePassphrase.previousKeyLost && !DatabaseEncryption.isPlaintext(databaseFile)) {
            // The file on disk was encrypted under a key this device no longer
            // has. Opening it will never succeed again, so it is moved out of
            // the way and a fresh database is built — otherwise every launch
            // would fail identically, forever.
            DatabaseEncryption.setAside(databaseFile)
        }

        // Existing installs have a readable database sitting there already. It
        // has to be converted before Room tries to open it with a key.
        if (DatabaseEncryption.isPlaintext(databaseFile)) {
            DatabaseEncryption.encryptInPlace(databaseFile, passphrase)
        }

        // asBytes() hands over a fresh copy each time — SQLCipher zeroes the
        // array it is given, so a shared one would arrive blank.
        return Room.databaseBuilder(context, SpendWiseDatabase::class.java, DATABASE_NAME)
            .openHelperFactory(SupportOpenHelperFactory(databasePassphrase.asBytes()))
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                MIGRATION_13_14
            )
            .build()
    }

    private const val DATABASE_NAME = "spendwise.db"

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

    @Provides
    fun provideCategoryBudgetDao(database: SpendWiseDatabase): CategoryBudgetDao =
        database.categoryBudgetDao()

    @Provides
    fun provideAssetDao(database: SpendWiseDatabase): AssetDao = database.assetDao()

    @Provides
    fun provideLoanDetailsDao(database: SpendWiseDatabase): LoanDetailsDao =
        database.loanDetailsDao()
}
