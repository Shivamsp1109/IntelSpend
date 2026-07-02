package com.spendwise.data.remote

import com.spendwise.data.local.GoalEntity

data class GoalSyncPayload(
    val uid: String,
    val localId: Int,
    val type: String,
    val targetAmount: Double,
    val targetDate: Long,
    val currentSaved: Double,
    val monthlyContribution: Double
)

fun GoalEntity.toSyncPayload(uid: String): GoalSyncPayload = GoalSyncPayload(
    uid = uid,
    localId = id,
    type = type,
    targetAmount = targetAmount,
    targetDate = targetDate,
    currentSaved = currentSaved,
    monthlyContribution = monthlyContribution
)
