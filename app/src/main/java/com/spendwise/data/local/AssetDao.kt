package com.spendwise.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AssetDao {

    /** Largest first: someone opening this screen is looking for what matters. */
    @Query("SELECT * FROM assets ORDER BY currentValue DESC")
    fun observeAssets(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE id = :id")
    suspend fun getById(id: Int): AssetEntity?

    @Query("SELECT * FROM assets")
    suspend fun getAll(): List<AssetEntity>

    @Query("SELECT * FROM assets WHERE isSynced = 0")
    suspend fun getPendingSync(): List<AssetEntity>

    /** See ExpenseDao.markSynced — one invalidation for a batch, not one per row. */
    @Query("UPDATE assets SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Int>)

    /** Whether this device holds anything yet; gates restore-from-server. */
    @Query("SELECT COUNT(*) FROM assets")
    suspend fun countAssets(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAsset(asset: AssetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssets(assets: List<AssetEntity>)

    @Update
    suspend fun updateAsset(asset: AssetEntity)

    @Delete
    suspend fun deleteAsset(asset: AssetEntity)
}
