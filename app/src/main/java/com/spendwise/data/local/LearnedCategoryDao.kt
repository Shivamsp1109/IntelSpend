package com.spendwise.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LearnedCategoryDao {
    @Query("SELECT category FROM learned_categories WHERE merchant = :merchant LIMIT 1")
    suspend fun getCategoryForMerchant(merchant: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLearnedCategory(category: LearnedCategoryEntity)
}
