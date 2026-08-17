package com.spendwise.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LoanDetailsDao {

    @Query("SELECT * FROM loan_details")
    fun observeLoanDetails(): Flow<List<LoanDetailsEntity>>

    /** The terms for one commitment, for the screen that edits them. */
    @Query("SELECT * FROM loan_details WHERE recurringId = :recurringId LIMIT 1")
    fun observeForRecurring(recurringId: Int): Flow<LoanDetailsEntity?>

    @Query("SELECT * FROM loan_details WHERE recurringId = :recurringId LIMIT 1")
    suspend fun getForRecurring(recurringId: Int): LoanDetailsEntity?

    @Query("SELECT * FROM loan_details")
    suspend fun getAll(): List<LoanDetailsEntity>

    @Query("SELECT * FROM loan_details WHERE isSynced = 0")
    suspend fun getPendingSync(): List<LoanDetailsEntity>

    /** See ExpenseDao.markSynced — one invalidation for a batch, not one per row. */
    @Query("UPDATE loan_details SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Int>)

    @Query("SELECT COUNT(*) FROM loan_details")
    suspend fun countLoanDetails(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLoanDetails(loan: LoanDetailsEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(loans: List<LoanDetailsEntity>)

    @Update
    suspend fun updateLoanDetails(loan: LoanDetailsEntity)

    @Delete
    suspend fun deleteLoanDetails(loan: LoanDetailsEntity)
}
