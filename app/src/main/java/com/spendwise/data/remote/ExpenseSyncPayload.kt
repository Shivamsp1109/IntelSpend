package com.spendwise.data.remote

import com.spendwise.data.local.ExpenseEntity

data class ExpenseSyncPayload(
    val uid: String,
    val localId: Int,
    val title: String,
    val amount: Double,
    val category: String,
    val date: Long,
    val merchant: String?,
    val currency: String,
    val source: String,
    /**
     * Bank or UPI reference, so duplicate detection still works after a restore.
     *
     * Without it a reinstalled device would come back with every reference
     * blank, and importing a statement covering already-recorded payments would
     * fall back to matching on merchant text — the case this field exists to
     * make reliable.
     */
    val reference: String?,
    /**
     * Whether the date was substituted at import rather than read.
     *
     * Synced because it cannot be reconstructed: a restored device that assumed
     * every date was real would start letting guessed dates rule out duplicate
     * matches again.
     */
    val dateIsAssumed: Boolean
)

fun ExpenseEntity.toSyncPayload(uid: String): ExpenseSyncPayload = ExpenseSyncPayload(
    uid = uid,
    localId = id,
    title = title,
    amount = amount,
    category = category,
    date = date,
    merchant = merchant,
    currency = currency,
    source = source,
    reference = reference,
    dateIsAssumed = dateIsAssumed
)

