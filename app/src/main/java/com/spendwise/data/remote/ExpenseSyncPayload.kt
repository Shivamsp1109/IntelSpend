package com.spendwise.data.remote

import com.spendwise.data.local.ExpenseEntity

data class ExpenseSyncPayload(
    val uid: String,
    val localId: Int,
    val title: String,
    val amount: Double,
    val category: String,
    val date: Long
)

fun ExpenseEntity.toSyncPayload(uid: String): ExpenseSyncPayload = ExpenseSyncPayload(
    uid = uid,
    localId = id,
    title = title,
    amount = amount,
    category = category,
    date = date
)
