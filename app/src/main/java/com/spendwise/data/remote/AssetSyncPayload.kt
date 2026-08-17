package com.spendwise.data.remote

import com.spendwise.data.local.AssetEntity
import com.spendwise.domain.model.Asset
import com.spendwise.domain.model.AssetOwnership
import com.spendwise.domain.model.AssetType
import com.spendwise.domain.model.AssetVerification
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.LiquidityClass

/**
 * A holding on its way to the server.
 *
 * The value travels as a decimal string rather than a number. JSON numbers are
 * doubles, so ₹1,99,999.99 can arrive a paisa light — too small to notice and
 * enough to make two reads of the same holding disagree, which is exactly the
 * class of error the engine's minor-unit arithmetic exists to remove. Sending it
 * as text keeps the value exact all the way to the DECIMAL column.
 */
data class AssetSyncPayload(
    val uid: String,
    val localId: Int,
    val assetType: String,
    val label: String,
    val currentValue: String,
    val currency: String,
    val valuationDate: Long,
    val liquidityClass: String,
    val lockInUntil: Long?,
    val ownership: String,
    val verificationSource: String,
    val accountType: String?
)

/** Two decimal places, formatted without going through a float. */
private fun Double.toDecimalString(): String = String.format(java.util.Locale.US, "%.2f", this)

fun AssetEntity.toSyncPayload(uid: String): AssetSyncPayload = AssetSyncPayload(
    uid = uid,
    localId = id,
    assetType = type,
    label = label,
    currentValue = currentValue.toDecimalString(),
    currency = currency,
    valuationDate = valuationDate,
    liquidityClass = liquidityClass,
    lockInUntil = lockInUntil,
    ownership = ownership,
    verificationSource = verificationSource,
    accountType = accountType
)

/** One holding as the restore endpoint returns it. */
data class AssetRestorePayload(
    val localId: Int,
    val assetType: String?,
    val label: String?,
    val currentValue: String?,
    val currency: String?,
    val valuationDate: Long?,
    val liquidityClass: String?,
    val lockInUntil: Long?,
    val ownership: String?,
    val verificationSource: String?,
    val accountType: String?
)

/**
 * Rebuilt field by field rather than trusted wholesale.
 *
 * Every enum goes through its own `fromName`, which falls back to a safe value
 * rather than throwing — a server that has learned a new asset type before this
 * build has should degrade to OTHER, not crash a restore halfway through and
 * leave the device half-populated.
 */
fun AssetRestorePayload.toDomain(): Asset = Asset(
    id = localId,
    label = label.orEmpty().ifBlank { "Untitled holding" },
    type = AssetType.fromName(assetType.orEmpty()),
    currentValue = currentValue?.toDoubleOrNull() ?: 0.0,
    currency = Currency.fromCode(currency ?: Currency.INR.code),
    valuationDate = valuationDate ?: System.currentTimeMillis(),
    liquidity = LiquidityClass.fromName(liquidityClass.orEmpty()),
    lockInUntil = lockInUntil,
    ownership = AssetOwnership.fromName(ownership.orEmpty()),
    verificationSource = AssetVerification.fromName(verificationSource.orEmpty()),
    accountType = accountType,
    // Came from the server, so it is already there.
    isSynced = true
)
