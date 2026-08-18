package com.spendwise.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.InsurancePolicy
import com.spendwise.domain.model.InsuranceType
import com.spendwise.domain.model.PremiumCadence

@Entity(
    tableName = "insurance_policies",
    indices = [Index(value = ["type"]), Index(value = ["isSynced"])]
)
data class InsurancePolicyEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val label: String,
    val type: String = InsuranceType.OTHER.name,
    val provider: String? = null,
    val sumAssured: Double,
    val currency: String = Currency.INR.code,
    val premiumAmount: Double? = null,
    val premiumCadence: String = PremiumCadence.YEARLY.name,
    val policyEndDate: Long? = null,
    /** Nullable on purpose: unknown is not the same as no nominee. */
    val nomineeSet: Boolean? = null,
    val isSynced: Boolean = false
)

fun InsurancePolicyEntity.toDomain(): InsurancePolicy = InsurancePolicy(
    id = id,
    label = label,
    type = InsuranceType.fromName(type),
    provider = provider,
    sumAssured = sumAssured,
    currency = Currency.fromCode(currency),
    premiumAmount = premiumAmount,
    premiumCadence = PremiumCadence.fromName(premiumCadence),
    policyEndDate = policyEndDate,
    nomineeSet = nomineeSet,
    isSynced = isSynced
)

fun InsurancePolicy.toEntity(): InsurancePolicyEntity = InsurancePolicyEntity(
    id = id,
    label = label,
    type = type.name,
    provider = provider,
    sumAssured = sumAssured,
    currency = currency.code,
    premiumAmount = premiumAmount,
    premiumCadence = premiumCadence.name,
    policyEndDate = policyEndDate,
    nomineeSet = nomineeSet,
    isSynced = isSynced
)
