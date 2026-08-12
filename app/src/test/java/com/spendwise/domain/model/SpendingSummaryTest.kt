package com.spendwise.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Derived figures on the summary — the numbers a user reads off the screen. */
class SpendingSummaryTest {

    private fun summary(
        expense: Double = 0.0,
        income: Double = 0.0,
        previousExpense: Double = 0.0,
        previousIncome: Double = 0.0,
        count: Int = 0,
        days: Int = 31
    ) = SpendingSummary(
        currency = Currency.INR,
        range = DateRange(0L, days * 86_400_000L - 1),
        totalExpense = expense,
        totalIncome = income,
        previousExpense = previousExpense,
        previousIncome = previousIncome,
        transactionCount = count
    )

    @Test
    fun `savings rate is the share of income kept`() {
        assertEquals(0.25, summary(expense = 75_000.0, income = 100_000.0).savingsRate!!, 0.0001)
    }

    /**
     * Zero would render as "you saved 0%", which is a claim about behaviour.
     * With no income recorded there is nothing to take a share of.
     */
    @Test
    fun `savings rate is null when no income is recorded`() {
        assertNull(summary(expense = 5_000.0, income = 0.0).savingsRate)
    }

    @Test
    fun `savings rate goes negative when spending exceeds income`() {
        assertEquals(-0.5, summary(expense = 30_000.0, income = 20_000.0).savingsRate!!, 0.0001)
    }

    @Test
    fun `expense change compares against the previous period`() {
        val s = summary(expense = 12_000.0, previousExpense = 10_000.0)
        assertEquals(2_000.0, s.expenseChange, 0.0001)
        assertEquals(0.2, s.expenseChangePercent!!, 0.0001)
    }

    /** A first-ever period has no base, and dividing by zero would report infinity. */
    @Test
    fun `change percent is null with no previous spending`() {
        assertNull(summary(expense = 12_000.0, previousExpense = 0.0).expenseChangePercent)
    }

    @Test
    fun `averages divide by day count and transaction count`() {
        val s = summary(expense = 31_000.0, count = 10, days = 31)
        assertEquals(1_000.0, s.averagePerDay, 0.0001)
        assertEquals(3_100.0, s.averagePerTransaction, 0.0001)
    }

    @Test
    fun `averages are zero rather than NaN when there is nothing to divide`() {
        val s = summary(expense = 0.0, count = 0)
        assertEquals(0.0, s.averagePerTransaction, 0.0001)
    }

    @Test
    fun `net is income minus expense`() {
        assertEquals(25_000.0, summary(expense = 75_000.0, income = 100_000.0).net, 0.0001)
    }

    @Test
    fun `merchant average exposes small frequent spending`() {
        val swiggy = MerchantSpend(name = "Swiggy", total = 8_000.0, transactionCount = 40)
        assertEquals(200.0, swiggy.averageTransaction, 0.0001)
        assertEquals(0.0, MerchantSpend("None", 0.0, 0).averageTransaction, 0.0001)
    }
}
