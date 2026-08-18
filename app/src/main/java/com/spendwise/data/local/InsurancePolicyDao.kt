package com.spendwise.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface InsurancePolicyDao {

    /** Largest cover first — the policies that matter most in a crisis. */
    @Query("SELECT * FROM insurance_policies ORDER BY sumAssured DESC")
    fun observePolicies(): Flow<List<InsurancePolicyEntity>>

    @Query("SELECT * FROM insurance_policies WHERE id = :id")
    suspend fun getById(id: Int): InsurancePolicyEntity?

    @Query("SELECT * FROM insurance_policies")
    suspend fun getAll(): List<InsurancePolicyEntity>

    @Query("SELECT * FROM insurance_policies WHERE isSynced = 0")
    suspend fun getPendingSync(): List<InsurancePolicyEntity>

    /** See ExpenseDao.markSynced — one invalidation for a batch, not one per row. */
    @Query("UPDATE insurance_policies SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Int>)

    @Query("SELECT COUNT(*) FROM insurance_policies")
    suspend fun countPolicies(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPolicy(policy: InsurancePolicyEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPolicies(policies: List<InsurancePolicyEntity>)

    @Update
    suspend fun updatePolicy(policy: InsurancePolicyEntity)

    @Delete
    suspend fun deletePolicy(policy: InsurancePolicyEntity)
}
