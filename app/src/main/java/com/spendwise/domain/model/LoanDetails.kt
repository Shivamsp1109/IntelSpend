package com.spendwise.domain.model

/**
 * The terms behind a tracked loan commitment.
 *
 * A [RecurringEntry] with nature LoanRepayment already records what leaves the
 * account each month. That is enough to count the EMI in someone's outgoings and
 * no more — it cannot say what is still owed, what the borrowing costs, when it
 * ends, or whether paying it down early is worth doing. Every one of those needs
 * the loan's terms rather than its payment history.
 *
 * So this hangs off the commitment rather than replacing it, and it is optional.
 * Someone who never enters their interest rate should still have their EMI
 * counted; the engine's job there is to say what it cannot work out, not to
 * assume a plausible rate and present the result as fact.
 *
 * Nearly every field is nullable for that reason. People know their EMI and
 * often not their APR, and that is a normal state to be in, not a broken record.
 */
data class LoanDetails(
    val id: Int = 0,
    /** The commitment these terms belong to. */
    val recurringId: Int,
    val principalOutstanding: Double,
    /** When the balance was last read. A figure ages, and the engine says so. */
    val outstandingAsOf: Long,
    val currency: Currency = Currency.INR,

    /** Annual nominal rate as a percentage, e.g. 8.75. Null when not known. */
    val interestRate: Double? = null,
    val rateType: RateType = RateType.UNKNOWN,
    /** When a variable rate is next repriced. A projection past it is guesswork. */
    val rateResetDate: Long? = null,
    val compounding: InterestCompounding = InterestCompounding.UNKNOWN,

    /**
     * The contractual payment, which is not always what the commitment observed:
     * a part-payment or a bounced month makes the two differ, and a schedule
     * should be computed from the contract.
     */
    val scheduledPayment: Double? = null,
    val paymentFrequency: RecurringCadence = RecurringCadence.MONTHLY,
    val remainingInstallments: Int? = null,
    val nextPaymentDate: Long? = null,

    val prepaymentChargeType: PrepaymentChargeType = PrepaymentChargeType.UNKNOWN,
    val prepaymentChargeValue: Double? = null,
    /** Standing charges the schedule misses, so a total cost can say it excluded them. */
    val feesOrPenalties: Double? = null,

    val isSynced: Boolean = false
) {
    /**
     * Whether there is enough here to say what the borrowing will cost.
     *
     * Either route works: a payment and a remaining count give the answer by
     * arithmetic whatever the rate is, while a payment and a rate let the term
     * be derived. With neither, the engine returns nothing rather than an
     * estimate dressed as a figure.
     */
    val canProjectCost: Boolean
        get() = scheduledPayment != null &&
            (remainingInstallments != null || (interestRate != null && rateType != RateType.UNKNOWN))
}

enum class RateType(val label: String) {
    FIXED("Fixed"),
    VARIABLE("Variable"),
    UNKNOWN("Not sure");

    companion object {
        fun fromName(name: String): RateType =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

enum class InterestCompounding(val label: String) {
    MONTHLY("Monthly"),
    ANNUAL("Yearly"),
    UNKNOWN("Not sure");

    companion object {
        fun fromName(name: String): InterestCompounding =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * How an early-repayment charge is worked out.
 *
 * Enumerated rather than free text because a payoff comparison has to subtract
 * it. "2% or ₹5,000 whichever is higher" typed into a box cannot drive a
 * deterministic figure, so anything unrecognised stays UNKNOWN and the engine
 * declines to state a net saving — rather than quietly reporting a benefit that
 * a fee would have wiped out.
 */
enum class PrepaymentChargeType(val label: String) {
    NONE("No charge"),
    FLAT("A fixed fee"),
    PERCENT_OF_PRINCIPAL("A percentage"),
    UNKNOWN("Not sure");

    companion object {
        fun fromName(name: String): PrepaymentChargeType =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}
