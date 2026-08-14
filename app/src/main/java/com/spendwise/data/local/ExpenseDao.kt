package com.spendwise.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow

/** One merchant's transactions that currently share a category and nature. */
data class MerchantGroupRow(
    val merchant: String,
    val category: String,
    val nature: String,
    val count: Int,
    val total: Double,
    val latestDate: Long,
    val currency: String
)

/** A single dated payment, for working out whether a merchant repeats. */
data class ExpenseTimeSeriesRow(
    val expenseId: Int,
    val merchant: String,
    val date: Long,
    val amount: Double,
    val category: String,
    val nature: String,
    val currency: String
)

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY date DESC")
    fun observeExpenses(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE date >= :startDate AND date <= :endDate ORDER BY date DESC")
    suspend fun getExpensesBetweenDates(startDate: Long, endDate: Long): List<ExpenseEntity>

    @androidx.room.RawQuery(observedEntities = [ExpenseEntity::class])
    fun pagingSourceRaw(query: androidx.sqlite.db.SupportSQLiteQuery): PagingSource<Int, ExpenseEntity>

    @Query("SELECT DISTINCT title FROM expenses WHERE title IS NOT NULL AND title != '' ORDER BY title ASC")
    fun getDistinctTitles(): Flow<List<String>>

    @Query("SELECT DISTINCT category FROM expenses WHERE category IS NOT NULL AND category != '' ORDER BY category ASC")
    fun getDistinctCategories(): Flow<List<String>>

    @Query("SELECT DISTINCT merchant FROM expenses WHERE merchant IS NOT NULL AND merchant != '' ORDER BY merchant ASC")
    fun getDistinctMerchants(): Flow<List<String>>

    @Query("SELECT DISTINCT currency FROM expenses WHERE currency IS NOT NULL AND currency != '' ORDER BY currency ASC")
    fun getDistinctCurrencies(): Flow<List<String>>

    @Query("SELECT * FROM expenses WHERE isSynced = 0 ORDER BY date ASC")
    suspend fun getPendingSync(): List<ExpenseEntity>

    /** Whether this device holds anything yet; gates restore-from-server. */
    @Query("SELECT COUNT(*) FROM expenses")
    suspend fun countExpenses(): Int

    /**
     * Expenses grouped by merchant, for fixing a whole merchant in one go.
     *
     * Grouped rather than listed because that is the shape of the problem:
     * categorisation is wrong per-merchant, so every "HDFC CC PAYMENT" row is
     * wrong in the same way. Ticking forty rows to fix one mistake is work the
     * app should be doing.
     *
     * Rows without a merchant are excluded — they cannot be fixed as a group,
     * only individually, and a nameless bucket at the top of the list is noise.
     */
    @Query(
        """
        SELECT merchant AS merchant,
               category AS category,
               nature   AS nature,
               COUNT(*) AS count,
               SUM(amount) AS total,
               MAX(date) AS latestDate,
               currency AS currency
        FROM expenses
        WHERE merchant IS NOT NULL AND merchant != ''
        GROUP BY merchant, category, nature, currency
        ORDER BY total DESC
        """
    )
    fun observeMerchantGroups(): Flow<List<MerchantGroupRow>>

    /**
     * Individual dated payments per merchant, for recurring-payment detection.
     *
     * Deliberately not the grouped query above. Working out that something
     * repeats monthly needs the gaps *between* payments, and an aggregate has
     * already thrown those away — a count and a total cannot tell rent from
     * thirty unrelated purchases at the same shop.
     *
     * Four natures qualify, not just spending. An EMI is a LoanRepayment and a
     * monthly SIP is an Investment; both are commitments the user must meet, and
     * filtering to Spending alone — as every other list in this app does — would
     * make the two most important fixed payments in a household budget invisible
     * to the one feature meant to find them.
     *
     * CreditCardPayment is excluded on purpose. A card bill arrives every month
     * like clockwork, but its amount is whatever was spent that month, so it is
     * a repeating *event* rather than a fixed commitment, and treating it as one
     * would put a number in the user's obligations that means nothing.
     */
    @Query(
        """
        SELECT id       AS expenseId,
               merchant AS merchant,
               date     AS date,
               amount   AS amount,
               category AS category,
               nature   AS nature,
               currency AS currency
        FROM expenses
        WHERE merchant IS NOT NULL AND merchant != ''
          AND nature IN ('Spending', 'LoanRepayment', 'Investment', 'Savings')
        ORDER BY merchant ASC, date ASC
        """
    )
    fun observeExpenseTimeSeries(): Flow<List<ExpenseTimeSeriesRow>>

    /**
     * Payments not yet attributed to any tracked commitment.
     *
     * What reconciliation works from. A commitment only knows a payment has gone
     * out if something links the two, and nothing does that at import time —
     * imports arrive in bulk, out of order, and often before the commitment was
     * confirmed at all. Sweeping the unlinked rows afterwards repairs all three
     * cases with one pass, and is safely repeatable because a linked row stops
     * appearing here.
     */
    @Query(
        """
        SELECT id       AS expenseId,
               merchant AS merchant,
               date     AS date,
               amount   AS amount,
               category AS category,
               nature   AS nature,
               currency AS currency
        FROM expenses
        WHERE merchant IS NOT NULL AND merchant != ''
          AND nature IN ('Spending', 'LoanRepayment', 'Investment', 'Savings')
          AND id NOT IN (SELECT expenseId FROM recurring_expense_cross_ref)
        ORDER BY date ASC
        """
    )
    suspend fun getUnlinkedExpenses(): List<ExpenseTimeSeriesRow>

    /**
     * Re-files every row for one merchant that currently sits under the given
     * category and nature.
     *
     * Scoped to the group the user was actually looking at rather than to the
     * merchant alone: if the same shop legitimately has both a Spending and a
     * Refund row, fixing one must not silently rewrite the other.
     *
     * Marked unsynced so the change reaches the server on the next sweep.
     */
    @Query(
        """
        UPDATE expenses
           SET category = :newCategory,
               nature = :newNature,
               isSynced = 0
         WHERE merchant = :merchant
           AND category = :fromCategory
           AND nature = :fromNature
        """
    )
    suspend fun recategoriseMerchant(
        merchant: String,
        fromCategory: String,
        fromNature: String,
        newCategory: String,
        newNature: String
    ): Int

    /**
     * Rows whose date was substituted at import.
     *
     * Duplicate detection needs these outside any date window, because their
     * stored date reflects when they were scanned rather than when the payment
     * happened — so a range query around the real date will never find them.
     */
    @Query("SELECT * FROM expenses WHERE dateIsAssumed = 1")
    suspend fun getExpensesWithAssumedDate(): List<ExpenseEntity>

    @Query("SELECT * FROM expense_delete_sync_queue ORDER BY createdAt ASC")
    suspend fun getPendingDeleteSync(): List<ExpenseDeleteSyncEntity>

    @Query("SELECT (SELECT COUNT(*) FROM expenses WHERE isSynced = 0) + (SELECT COUNT(*) FROM expense_delete_sync_queue)")
    fun observePendingSyncCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpenses(expenses: List<ExpenseEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingDelete(delete: ExpenseDeleteSyncEntity)

    @Update
    suspend fun updateExpense(expense: ExpenseEntity)

    @Delete
    suspend fun deleteExpense(expense: ExpenseEntity)

    @Query("DELETE FROM expense_delete_sync_queue WHERE localId = :localId")
    suspend fun deletePendingDelete(localId: Int)
}
