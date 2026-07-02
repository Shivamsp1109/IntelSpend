package com.spendwise.data.remote

import com.spendwise.data.local.IncomeEntity

data class IncomeSyncPayload(
    val uid: String,
    val localId: Int,
    val title: String,
    val amount: Double,
    val currency: String,
    val source: String,
    val note: String?,
    val date: Long
)

fun IncomeEntity.toSyncPayload(uid: String): IncomeSyncPayload = IncomeSyncPayload(
    uid = uid,
    localId = id,
    title = title,
    amount = amount,
    currency = currency,
    source = source,
    note = note,
    date = date
)
