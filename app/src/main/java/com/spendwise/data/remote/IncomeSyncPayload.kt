package com.spendwise.data.remote

import com.spendwise.data.local.IncomeEntity

/**
 * One page of restored records.
 *
 * [nextAfter] is the cursor for the following page, or null on the last one.
 * A cursor rather than a page number because an interrupted restore has to
 * resume from what it actually stored, and because rows written between two
 * requests would shift an offset window and skip an unrelated row.
 */
data class RestorePage<T>(
    val items: List<T> = emptyList(),
    val nextAfter: Int? = null
)

data class IncomeSyncPayload(
    val uid: String,
    val localId: Int,
    val title: String,
    val amount: Double,
    val currency: String,
    val source: String,
    val note: String?,
    val date: Long,
    /** Bank or UPI reference; see ExpenseSyncPayload.reference. */
    val reference: String?,
    /** See ExpenseSyncPayload.dateIsAssumed. */
    val dateIsAssumed: Boolean
)

fun IncomeEntity.toSyncPayload(uid: String): IncomeSyncPayload = IncomeSyncPayload(
    uid = uid,
    localId = id,
    title = title,
    amount = amount,
    currency = currency,
    source = source,
    note = note,
    date = date,
    reference = reference,
    dateIsAssumed = dateIsAssumed
)
