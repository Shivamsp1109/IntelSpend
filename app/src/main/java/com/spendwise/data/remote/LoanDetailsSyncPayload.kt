package com.spendwise.data.remote

import com.spendwise.data.local.LoanDetailsEntity
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.InterestCompounding
import com.spendwise.domain.model.LoanDetails
import com.spendwise.domain.model.PrepaymentChargeType
import com.spendwise.domain.model.RateType
import com.spendwise.domain.model.RecurringCadence
import java.util.Locale

/**
 * Loan terms on their way to the server.
 *
 * Money travels as a decimal string for the same reason as everywhere else in
 * this engine: a JSON number is a double, and a balance that drifts by a paisa
 * between reads makes two payoff comparisons of the same loan disagree.
 *
 * Nulls are sent as nulls rather than defaulted to zero or "UNKNOWN" on the way
 * out. An absent rate and a rate of nought are different claims, and the whole
 * discipline of the debt engine rests on being able to tell them apart.
 */
data class LoanDetailsSyncPayload(
    val uid: String,
    val recurringLocalId: Int,
    val principalOutstanding: String,
    val outstandingAsOf: Long,
    val currency: String,
    val interestRate: String?,
    val rateType: String,
    val rateResetDate: Long?,
    val interestCompounding: String,
    val scheduledPayment: String?,
    val paymentFrequency: String,
    val remainingInstallments: Int?,
    val nextPaymentDate: Long?,
    val prepaymentChargeType: String,
    val prepaymentChargeValue: String?,
    val feesOrPenalties: String?
)

private fun Double.toDecimalString(places: Int = 2): String =
    String.format(Locale.US, "%.${places}f", this)

fun LoanDetailsEntity.toSyncPayload(uid: String): LoanDetailsSyncPayload = LoanDetailsSyncPayload(
    uid = uid,
    recurringLocalId = recurringId,
    principalOutstanding = principalOutstanding.toDecimalString(),
    outstandingAsOf = outstandingAsOf,
    currency = currency,
    // Four places: a rate of 8.7525% is a real quote, and rounding it to two
    // would change what the schedule costs.
    interestRate = interestRate?.toDecimalString(places = 4),
    rateType = rateType,
    rateResetDate = rateResetDate,
    interestCompounding = compounding,
    scheduledPayment = scheduledPayment?.toDecimalString(),
    paymentFrequency = paymentFrequency,
    remainingInstallments = remainingInstallments,
    nextPaymentDate = nextPaymentDate,
    prepaymentChargeType = prepaymentChargeType,
    prepaymentChargeValue = prepaymentChargeValue?.toDecimalString(places = 4),
    feesOrPenalties = feesOrPenalties?.toDecimalString()
)

/** One set of terms as the restore endpoint returns it. */
data class LoanDetailsRestorePayload(
    val recurringLocalId: Int,
    val principalOutstanding: String?,
    val outstandingAsOf: Long?,
    val currency: String?,
    val interestRate: String?,
    val rateType: String?,
    val rateResetDate: Long?,
    val interestCompounding: String?,
    val scheduledPayment: String?,
    val paymentFrequency: String?,
    val remainingInstallments: Int?,
    val nextPaymentDate: Long?,
    val prepaymentChargeType: String?,
    val prepaymentChargeValue: String?,
    val feesOrPenalties: String?
)

/**
 * Rebuilt field by field, with every absent value staying absent.
 *
 * `toDoubleOrNull` rather than `?: 0.0` on the optional figures: a rate that
 * failed to parse must not become an interest-free loan, and a missing payment
 * must not become a payment of nothing. Both would produce a confident, wrong
 * schedule instead of the refusal the engine is built to give.
 */
fun LoanDetailsRestorePayload.toDomain(): LoanDetails = LoanDetails(
    id = 0,
    recurringId = recurringLocalId,
    principalOutstanding = principalOutstanding?.toDoubleOrNull() ?: 0.0,
    outstandingAsOf = outstandingAsOf ?: System.currentTimeMillis(),
    currency = Currency.fromCode(currency ?: Currency.INR.code),
    interestRate = interestRate?.toDoubleOrNull(),
    rateType = RateType.fromName(rateType.orEmpty()),
    rateResetDate = rateResetDate,
    compounding = InterestCompounding.fromName(interestCompounding.orEmpty()),
    scheduledPayment = scheduledPayment?.toDoubleOrNull(),
    paymentFrequency = RecurringCadence.fromName(paymentFrequency.orEmpty()),
    remainingInstallments = remainingInstallments,
    nextPaymentDate = nextPaymentDate,
    prepaymentChargeType = PrepaymentChargeType.fromName(prepaymentChargeType.orEmpty()),
    prepaymentChargeValue = prepaymentChargeValue?.toDoubleOrNull(),
    feesOrPenalties = feesOrPenalties?.toDoubleOrNull(),
    isSynced = true
)
