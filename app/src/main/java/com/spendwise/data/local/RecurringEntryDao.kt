package com.spendwise.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecurringEntryDao {

    // ── RecurringEntity CRUD ──────────────────────────────────────────────────

    @Query("SELECT * FROM recurring ORDER BY title ASC")
    fun observeRecurring(): Flow<List<RecurringEntity>>

    @Query("SELECT * FROM recurring WHERE id = :id")
    suspend fun getById(id: Int): RecurringEntity?

    @Query("SELECT * FROM recurring WHERE isSynced = 0")
    suspend fun getPendingSync(): List<RecurringEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecurring(entry: RecurringEntity): Long

    @Update
    suspend fun updateRecurring(entry: RecurringEntity)

    @Delete
    suspend fun deleteRecurring(entry: RecurringEntity)

    // ── Cross-ref management ──────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkExpense(crossRef: RecurringExpenseCrossRef)

    @Delete
    suspend fun unlinkExpense(crossRef: RecurringExpenseCrossRef)

    /** All cross-refs for a recurring entry — use to bulk-unlink before deletion. */
    @Query("SELECT * FROM recurring_expense_cross_ref WHERE recurringId = :recurringId")
    suspend fun getCrossRefsFor(recurringId: Int): List<RecurringExpenseCrossRef>

    // ── Relation queries ──────────────────────────────────────────────────────

    /** Returns every [RecurringEntity] with its linked expense rows. */
    @Transaction
    @Query("SELECT * FROM recurring ORDER BY title ASC")
    fun observeRecurringWithExpenses(): Flow<List<RecurringWithExpenses>>

    /** Returns a single [RecurringEntity] with its linked expense rows. */
    @Transaction
    @Query("SELECT * FROM recurring WHERE id = :id")
    suspend fun getRecurringWithExpenses(id: Int): RecurringWithExpenses?

    /**
     * Returns the recurring entry that an expense is linked to, if any.
     * Useful for "show parent subscription" in the expense detail screen.
     */
    @Query(
        """
        SELECT r.* FROM recurring r
        INNER JOIN recurring_expense_cross_ref ref ON r.id = ref.recurringId
        WHERE ref.expenseId = :expenseId
        LIMIT 1
        """
    )
    suspend fun getRecurringForExpense(expenseId: Int): RecurringEntity?
}
