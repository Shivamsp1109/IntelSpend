package com.spendwise.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "expense_delete_sync_queue")
data class ExpenseDeleteSyncEntity(
    @PrimaryKey val localId: Int,
    val createdAt: Long = System.currentTimeMillis()
)
