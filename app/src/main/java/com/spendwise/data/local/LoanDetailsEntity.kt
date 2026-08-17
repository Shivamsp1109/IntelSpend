package com.spendwise.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.InterestCompounding
import com.spendwise.domain.model.LoanDetails
import com.spendwise.domain.model.PrepaymentChargeType
import com.spendwise.domain.model.RateType
import com.spendwise.domain.model.RecurringCadence

/**
 * Loan terms, hanging off the commitment that pays them.
 *
 * CASCADE on the foreign key: deleting a tracked EMI takes its terms with it
 * rather than leaving a balance behind with nothing paying it down. Unique on
 * the commitment, because one loan has one set of terms — a second row would
 * make every total ambiguous.
 */
@Entity(
    tableName = "loan_details",
    foreignKeys = [
        ForeignKey(
            entity = RecurringEntity::class,
            parentColumns = ["id"],
            childColumns = ["recurringId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["recurringId"], unique = true),
        Index(value = ["isSynced"])
    ]
)
data class LoanDetailsEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val recurringId: Int,
    val principalOutstanding: Double,
    val outstandingAsOf: Long,
    val currency: String = Currency.INR.code,
    val interestRate: Double? = null,
    val rateType: String = RateType.UNKNOWN.name,
    val rateResetDate: Long? = null,
    val compounding: String = InterestCompounding.UNKNOWN.name,
    val scheduledPayment: Double? = null,
    val paymentFrequency: String = RecurringCadence.MONTHLY.name,
    val remainingInstallments: Int? = null,
    val nextPaymentDate: Long? = null,
    val prepaymentChargeType: String = PrepaymentChargeType.UNKNOWN.name,
    val prepaymentChargeValue: Double? = null,
    val feesOrPenalties: Double? = null,
    val isSynced: Boolean = false
)

fun LoanDetailsEntity.toDomain(): LoanDetails = LoanDetails(
    id = id,
    recurringId = recurringId,
    principalOutstanding = principalOutstanding,
    outstandingAsOf = outstandingAsOf,
    currency = Currency.fromCode(currency),
    interestRate = interestRate,
    rateType = RateType.fromName(rateType),
    rateResetDate = rateResetDate,
    compounding = InterestCompounding.fromName(compounding),
    scheduledPayment = scheduledPayment,
    paymentFrequency = RecurringCadence.fromName(paymentFrequency),
    remainingInstallments = remainingInstallments,
    nextPaymentDate = nextPaymentDate,
    prepaymentChargeType = PrepaymentChargeType.fromName(prepaymentChargeType),
    prepaymentChargeValue = prepaymentChargeValue,
    feesOrPenalties = feesOrPenalties,
    isSynced = isSynced
)

fun LoanDetails.toEntity(): LoanDetailsEntity = LoanDetailsEntity(
    id = id,
    recurringId = recurringId,
    principalOutstanding = principalOutstanding,
    outstandingAsOf = outstandingAsOf,
    currency = currency.code,
    interestRate = interestRate,
    rateType = rateType.name,
    rateResetDate = rateResetDate,
    compounding = compounding.name,
    scheduledPayment = scheduledPayment,
    paymentFrequency = paymentFrequency.name,
    remainingInstallments = remainingInstallments,
    nextPaymentDate = nextPaymentDate,
    prepaymentChargeType = prepaymentChargeType.name,
    prepaymentChargeValue = prepaymentChargeValue,
    feesOrPenalties = feesOrPenalties,
    isSynced = isSynced
)
