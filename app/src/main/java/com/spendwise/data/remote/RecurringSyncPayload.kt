package com.spendwise.data.remote

import com.spendwise.data.local.DismissedRecurringCandidateEntity
import com.spendwise.data.local.RecurringEntity

data class RecurringSyncPayload(
    val uid: String,
    val localId: Int,
    val title: String,
    val amount: Double,
    val cadence: String,
    val type: String,
    val currency: String,
    val nature: String,
    val category: String,
    val source: String,
    val occurrenceCount: Int,
    val confidence: Double
)

data class RecurringLinkPayload(
    val uid: String,
    val recurringLocalId: Int,
    val expenseLocalId: Int
)

/**
 * A detection the user rejected.
 *
 * Synced so a reinstall does not put every already-rejected suggestion back in
 * front of them — being asked the same questions again after a restore is the
 * fastest way to make a feature feel broken.
 */
data class RecurringDismissalPayload(
    val uid: String,
    val signature: String,
    val merchant: String,
    val currency: String,
    val nature: String,
    val category: String,
    val cadence: String,
    val lastSeenAmount: Double,
    val lastSeenOccurrenceDate: Long,
    val dismissedAt: Long
)

fun DismissedRecurringCandidateEntity.toSyncPayload(uid: String): RecurringDismissalPayload =
    RecurringDismissalPayload(
        uid = uid,
        signature = signature,
        merchant = merchant,
        currency = currency,
        nature = nature,
        category = category,
        cadence = cadence,
        lastSeenAmount = lastSeenAmount,
        lastSeenOccurrenceDate = lastSeenOccurrenceDate,
        dismissedAt = dismissedAt
    )

fun RecurringEntity.toSyncPayload(uid: String): RecurringSyncPayload = RecurringSyncPayload(
    uid = uid,
    localId = id,
    title = title,
    amount = amount,
    cadence = cadence,
    type = type,
    currency = currency,
    nature = nature,
    category = category,
    source = source,
    occurrenceCount = occurrenceCount,
    confidence = confidence
)
