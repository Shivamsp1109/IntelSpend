package com.spendwise.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses ORDER BY date DESC")
    fun observeExpenses(): Flow<List<ExpenseEntity>>

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

    @Query("SELECT * FROM expense_delete_sync_queue ORDER BY createdAt ASC")
    suspend fun getPendingDeleteSync(): List<ExpenseDeleteSyncEntity>

    @Query("SELECT (SELECT COUNT(*) FROM expenses WHERE isSynced = 0) + (SELECT COUNT(*) FROM expense_delete_sync_queue)")
    fun observePendingSyncCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingDelete(delete: ExpenseDeleteSyncEntity)

    @Update
    suspend fun updateExpense(expense: ExpenseEntity)

    @Delete
    suspend fun deleteExpense(expense: ExpenseEntity)

    @Query("DELETE FROM expense_delete_sync_queue WHERE localId = :localId")
    suspend fun deletePendingDelete(localId: Int)
}
