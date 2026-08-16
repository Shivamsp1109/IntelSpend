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
    val confidence: Double,
    val status: String,
    val lastOccurrenceDate: Long?,
    val nextDueDate: Long?,
    val dueDayOfMonth: Int?,
    val pendingAmount: Double?,
    val declinedAmount: Double?
)

/** One payment, and the commitment it settled. */
data class RecurringLinkRow(
    val localId: Int,
    val recurringLocalId: Int
)

/**
 * A stored dismissal, as it comes back for a restore.
 *
 * Separate from [RecurringDismissalPayload] rather than reused: that one carries
 * a uid, which the server never sends back and which would arrive null into a
 * non-null field — a crash at the worst possible moment, halfway through
 * rebuilding a device.
 */
data class RecurringDismissalRow(
    val localId: Int,
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
    confidence = confidence,
    status = status,
    lastOccurrenceDate = lastOccurrenceDate,
    nextDueDate = nextDueDate,
    dueDayOfMonth = dueDayOfMonth,
    pendingAmount = pendingAmount,
    declinedAmount = declinedAmount
)

/** See RestoreFromServerUseCase for why the id and sync flag are set this way. */
fun RecurringSyncPayload.toRestoredEntity() = RecurringEntity(
    id = localId,
    title = title,
    amount = amount,
    cadence = cadence,
    type = type,
    isSynced = true,
    currency = currency,
    nature = nature,
    category = category,
    source = source,
    occurrenceCount = occurrenceCount,
    confidence = confidence,
    status = status,
    lastOccurrenceDate = lastOccurrenceDate,
    nextDueDate = nextDueDate,
    dueDayOfMonth = dueDayOfMonth,
    pendingAmount = pendingAmount,
    declinedAmount = declinedAmount
)

fun RecurringDismissalRow.toRestoredEntity() = DismissedRecurringCandidateEntity(
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
