package com.spendwise.data.remote

import com.spendwise.data.local.RecurringEntity

data class RecurringSyncPayload(
    val uid: String,
    val localId: Int,
    val title: String,
    val amount: Double,
    val cadence: String,
    val type: String
)

data class RecurringLinkPayload(
    val uid: String,
    val recurringLocalId: Int,
    val expenseLocalId: Int
)

fun RecurringEntity.toSyncPayload(uid: String): RecurringSyncPayload = RecurringSyncPayload(
    uid = uid,
    localId = id,
    title = title,
    amount = amount,
    cadence = cadence,
    type = type
)
