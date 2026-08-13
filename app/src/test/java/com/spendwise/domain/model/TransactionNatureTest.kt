package com.spendwise.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a debit counts as money spent.
 *
 * The distinction exists because a statement import cannot tell the difference
 * on its own: a ₹50,000 transfer between two of the user's own accounts arrives
 * looking exactly like a ₹50,000 purchase. Counted, one such row makes a month
 * look ruinous and every chart on the analytics screen is wrong at once.
 */
class TransactionNatureTest {

    @Test
    fun `only spending counts as spending`() {
        assertTrue(TransactionNature.Spending.isSpending)

        val movements = listOf(
            TransactionNature.SelfTransfer,
            TransactionNature.LoanRepayment,
            TransactionNature.CreditCardPayment,
            TransactionNature.Investment,
            TransactionNature.Savings,
            TransactionNature.CashWithdrawal,
            TransactionNature.Refund,
            TransactionNature.Income
        )
        for (nature in movements) {
            assertFalse("${nature.name} must not count as spending", nature.isSpending)
        }
    }

    /**
     * Cash withdrawal is the subtle one. It is money leaving the account, but
     * what the cash buys is unknown — counting the withdrawal *and* any receipt
     * for that cash records the same money twice.
     */
    @Test
    fun `a cash withdrawal is not spending`() {
        assertFalse(TransactionNature.CashWithdrawal.isSpending)
    }

    @Test
    fun `names round trip`() {
        for (nature in TransactionNature.entries) {
            assertEquals(nature, TransactionNature.fromName(nature.name))
            assertEquals(nature, TransactionNature.fromName(nature.label))
        }
    }

    /**
     * Falls back to Spending rather than to a movement type. An unknown value
     * treated as a transfer would vanish from the user's totals silently; the
     * same value treated as spending is at worst visible and correctable.
     */
    @Test
    fun `an unrecognised value is treated as ordinary spending`() {
        assertEquals(TransactionNature.Spending, TransactionNature.fromName("Nonsense"))
        assertEquals(TransactionNature.Spending, TransactionNature.fromName(""))
    }

    @Test
    fun `matching ignores case and space`() {
        assertEquals(TransactionNature.SelfTransfer, TransactionNature.fromName("  selftransfer "))
        assertEquals(TransactionNature.CashWithdrawal, TransactionNature.fromName("CASHWITHDRAWAL"))
    }
}
