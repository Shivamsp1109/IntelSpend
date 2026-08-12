package com.spendwise.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface IncomeDao {

    @Query("SELECT * FROM incomes ORDER BY date DESC")
    fun observeIncomes(): Flow<List<IncomeEntity>>

    @Query("SELECT * FROM incomes WHERE date >= :startDate AND date <= :endDate ORDER BY date DESC")
    suspend fun getIncomesBetweenDates(startDate: Long, endDate: Long): List<IncomeEntity>

    @Query("SELECT * FROM incomes WHERE isSynced = 0 ORDER BY date ASC")
    suspend fun getPendingSync(): List<IncomeEntity>

    @Query("SELECT * FROM income_delete_sync_queue ORDER BY createdAt ASC")
    suspend fun getPendingDeleteSync(): List<IncomeDeleteSyncEntity>

    @Query("SELECT (SELECT COUNT(*) FROM incomes WHERE isSynced = 0) + (SELECT COUNT(*) FROM income_delete_sync_queue)")
    fun observePendingSyncCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM incomes
        WHERE (:query = '' OR title LIKE '%' || :query || '%')
        AND (:source IS NULL OR source = :source)
        ORDER BY date DESC
        """
    )
    fun searchIncomes(query: String, source: String?): Flow<List<IncomeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIncome(income: IncomeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIncomes(incomes: List<IncomeEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingDelete(delete: IncomeDeleteSyncEntity)

    @Update
    suspend fun updateIncome(income: IncomeEntity)

    @Delete
    suspend fun deleteIncome(income: IncomeEntity)

    @Query("DELETE FROM income_delete_sync_queue WHERE localId = :localId")
    suspend fun deletePendingDelete(localId: Int)
}
