package com.spendwise.data.remote

import com.spendwise.data.local.InsurancePolicyEntity
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.InsurancePolicy
import com.spendwise.domain.model.InsuranceType
import com.spendwise.domain.model.PremiumCadence
import java.util.Locale

/**
 * A policy on its way to the server.
 *
 * `nomineeSet` stays tri-state across the wire. Sending `false` for "we do not
 * know" would make the server warn about a missing nominee nobody has
 * established is missing.
 */
data class InsuranceSyncPayload(
    val uid: String,
    val localId: Int,
    val policyType: String,
    val provider: String?,
    val label: String,
    val sumAssured: String,
    val currency: String,
    val premiumAmount: String?,
    val premiumCadence: String,
    val policyEndDate: Long?,
    val nomineeSet: Boolean?
)

private fun Double.toDecimalString(): String = String.format(Locale.US, "%.2f", this)

fun InsurancePolicyEntity.toSyncPayload(uid: String): InsuranceSyncPayload = InsuranceSyncPayload(
    uid = uid,
    localId = id,
    policyType = type,
    provider = provider,
    label = label,
    sumAssured = sumAssured.toDecimalString(),
    currency = currency,
    premiumAmount = premiumAmount?.toDecimalString(),
    premiumCadence = premiumCadence,
    policyEndDate = policyEndDate,
    nomineeSet = nomineeSet
)

data class InsuranceRestorePayload(
    val localId: Int,
    val policyType: String?,
    val provider: String?,
    val label: String?,
    val sumAssured: String?,
    val currency: String?,
    val premiumAmount: String?,
    val premiumCadence: String?,
    val policyEndDate: Long?,
    val nomineeSet: Boolean?
)

fun InsuranceRestorePayload.toDomain(): InsurancePolicy = InsurancePolicy(
    id = localId,
    label = label.orEmpty().ifBlank { "Untitled policy" },
    type = InsuranceType.fromName(policyType.orEmpty()),
    provider = provider,
    sumAssured = sumAssured?.toDoubleOrNull() ?: 0.0,
    currency = Currency.fromCode(currency ?: Currency.INR.code),
    premiumAmount = premiumAmount?.toDoubleOrNull(),
    premiumCadence = PremiumCadence.fromName(premiumCadence.orEmpty()),
    policyEndDate = policyEndDate,
    nomineeSet = nomineeSet,
    isSynced = true
)
