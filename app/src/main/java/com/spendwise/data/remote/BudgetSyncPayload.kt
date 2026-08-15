package com.spendwise.data.remote

import com.spendwise.data.local.CategoryBudgetEntity

/**
 * A category budget on its way to the server.
 *
 * The alert-tracking fields are deliberately absent. Whether a notification has
 * been shown is a fact about a handset rather than the account — someone with a
 * phone and a tablet should be warned on both — so that state never leaves the
 * device.
 */
data class BudgetSyncPayload(
    val uid: String,
    val localId: Int,
    val category: String,
    val monthlyLimit: Double,
    val currency: String
)

fun CategoryBudgetEntity.toSyncPayload(uid: String): BudgetSyncPayload = BudgetSyncPayload(
    uid = uid,
    localId = id,
    category = category,
    monthlyLimit = monthlyLimit,
    currency = currency
)

/** See RestoreFromServerUseCase for why the id and sync flag are set this way. */
fun BudgetSyncPayload.toRestoredEntity() = CategoryBudgetEntity(
    id = localId,
    category = category,
    monthlyLimit = monthlyLimit,
    currency = currency,
    isSynced = true
)
