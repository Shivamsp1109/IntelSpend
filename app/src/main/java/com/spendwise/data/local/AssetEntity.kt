package com.spendwise.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.spendwise.domain.model.Asset
import com.spendwise.domain.model.AssetOwnership
import com.spendwise.domain.model.AssetType
import com.spendwise.domain.model.AssetVerification
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.LiquidityClass

/**
 * A holding, stored as the enum names rather than the display labels.
 *
 * Names, deliberately: labels are user-facing text and get reworded. The
 * category column learned that lesson the hard way — renaming a label there
 * would have left every existing row matching nothing and silently reclassified
 * a user's whole history on upgrade.
 *
 * Indexed on liquidity because the emergency-reserve question filters on it
 * every time it runs.
 */
@Entity(
    tableName = "assets",
    indices = [
        Index(value = ["liquidityClass"]),
        Index(value = ["isSynced"])
    ]
)
data class AssetEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val label: String,
    val type: String = AssetType.OTHER.name,
    val currentValue: Double,
    val currency: String = Currency.INR.code,
    val valuationDate: Long,
    val liquidityClass: String = LiquidityClass.ILLIQUID_INVESTMENT.name,
    val lockInUntil: Long? = null,
    val ownership: String = AssetOwnership.SELF.name,
    val verificationSource: String = AssetVerification.MANUAL.name,
    val accountType: String? = null,
    val isSynced: Boolean = false
)

fun AssetEntity.toDomain(): Asset = Asset(
    id = id,
    label = label,
    type = AssetType.fromName(type),
    currentValue = currentValue,
    currency = Currency.fromCode(currency),
    valuationDate = valuationDate,
    liquidity = LiquidityClass.fromName(liquidityClass),
    lockInUntil = lockInUntil,
    ownership = AssetOwnership.fromName(ownership),
    verificationSource = AssetVerification.fromName(verificationSource),
    accountType = accountType,
    isSynced = isSynced
)

fun Asset.toEntity(): AssetEntity = AssetEntity(
    id = id,
    label = label,
    type = type.name,
    currentValue = currentValue,
    currency = currency.code,
    valuationDate = valuationDate,
    liquidityClass = liquidity.name,
    lockInUntil = lockInUntil,
    ownership = ownership.name,
    verificationSource = verificationSource.name,
    accountType = accountType,
    isSynced = isSynced
)
