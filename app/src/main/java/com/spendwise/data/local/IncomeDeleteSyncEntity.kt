package com.spendwise.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "income_delete_sync_queue",
    indices = [Index(value = ["localId"], name = "index_income_delete_sync_queue_localId")]
)
data class IncomeDeleteSyncEntity(
    @PrimaryKey val localId: Int,
    val createdAt: Long = System.currentTimeMillis()
)
