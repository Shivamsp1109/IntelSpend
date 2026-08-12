package com.spendwise.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "learned_categories")
data class LearnedCategoryEntity(
    @PrimaryKey val merchant: String,
    val category: String,
    val updatedAt: Long
)
