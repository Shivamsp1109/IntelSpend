package com.spendwise.domain.model

/**
 * Whether money left the household, or merely moved.
 *
 * Kept apart from [ExpenseCategory] on purpose. Paying off a credit card, moving
 * savings between your own accounts, buying a mutual fund and taking cash out of
 * an ATM all appear on a statement as debits, and a statement import will pick
 * every one of them up. Treated as spending, a single ₹50,000 transfer between
 * two of your own accounts makes the month look catastrophic.
 *
 * Making them categories would not have helped: a category is still summed into
 * the total. The distinction has to be a separate axis so the aggregates can
 * leave them out, which is what [isSpending] exists for.
 */
enum class TransactionNature(val label: String) {

    /** Money actually spent on something. The default, and what analytics counts. */
    Spending("Spending"),

    /** Between the user's own accounts. Nets to nothing and must not be counted. */
    SelfTransfer("Transfer"),

    /**
     * Loan or EMI repayment.
     *
     * Arguably a real cost, but it repays a debt rather than buying anything,
     * and mixing it into category spending drowns out everything else. Tracked
     * separately so it can be reported in its own right.
     */
    LoanRepayment("Loan / EMI"),

    /** Paying a card bill. The purchases it settles were already counted. */
    CreditCardPayment("Credit card payment"),

    /** Into an investment. The money is still the user's. */
    Investment("Investment"),

    /** Into savings. Likewise still theirs. */
    Savings("Savings"),

    /**
     * Cash out of an ATM.
     *
     * Deliberately not spending. What the cash is later spent on is unknown, and
     * counting the withdrawal as well as any receipt for that cash would record
     * the same money twice.
     */
    CashWithdrawal("Cash withdrawal"),

    /** Money coming back. Reduces spending rather than adding to it. */
    Refund("Refund"),

    /** Money received. Belongs to income, not to the expense total. */
    Income("Income");

    /** Whether this should count towards what was spent. */
    val isSpending: Boolean get() = this == Spending

    companion object {
        fun fromName(name: String): TransactionNature {
            val trimmed = name.trim()
            return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
                ?: entries.firstOrNull { it.label.equals(trimmed, ignoreCase = true) }
                ?: Spending
        }
    }
}
