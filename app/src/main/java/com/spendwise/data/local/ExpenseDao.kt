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

    @Query(
        """
        SELECT * FROM expenses
        WHERE (:query = '' OR title LIKE '%' || :query || '%')
        AND (:category IS NULL OR category = :category)
        ORDER BY date DESC
        """
    )
    fun searchExpenses(query: String, category: String?): Flow<List<ExpenseEntity>>

    @Query(
        """
        SELECT * FROM expenses
        WHERE (:query = '' OR title LIKE '%' || :query || '%')
        AND (:category IS NULL OR category = :category)
        ORDER BY date DESC
        """
    )
    fun pagingSource(query: String, category: String?): PagingSource<Int, ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE isSynced = 0 ORDER BY date ASC")
    suspend fun getPendingSync(): List<ExpenseEntity>

    @Query("SELECT COUNT(*) FROM expenses WHERE isSynced = 0")
    fun observePendingSyncCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity): Long

    @Update
    suspend fun updateExpense(expense: ExpenseEntity)

    @Delete
    suspend fun deleteExpense(expense: ExpenseEntity)
}
