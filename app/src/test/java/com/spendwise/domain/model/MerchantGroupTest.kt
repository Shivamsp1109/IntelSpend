package com.spendwise.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordering the bulk-correction list.
 *
 * The screen exists because history cannot be reclassified retrospectively, so
 * the list starts long. What decides whether it gets used is whether the first
 * few entries are the ones worth fixing — a list that opens on eighty ₹40 auto
 * fares gets abandoned before reaching the ₹80,000 transfer sitting in the
 * spending total.
 */
class MerchantGroupTest {

    private fun group(
        merchant: String = "Swiggy",
        category: ExpenseCategory = ExpenseCategory.FoodDining,
        nature: TransactionNature = TransactionNature.Spending,
        count: Int = 1,
        total: Double = 1_000.0
    ) = MerchantGroup(
        merchant = merchant,
        category = category,
        nature = nature,
        count = count,
        total = total,
        latestDate = 0L,
        currency = Currency.INR
    )

    @Test
    fun `an uncategorised spending group needs attention`() {
        assertTrue(group(category = ExpenseCategory.Other).isUncategorised)
    }

    @Test
    fun `a categorised group does not`() {
        assertFalse(group(category = ExpenseCategory.Groceries).isUncategorised)
    }

    /**
     * Already excluded from totals, so its category is cosmetic — it is not
     * distorting anything and does not belong at the top of the queue.
     */
    @Test
    fun `an uncategorised transfer does not need attention`() {
        assertFalse(
            group(
                category = ExpenseCategory.Other,
                nature = TransactionNature.SelfTransfer
            ).isUncategorised
        )
    }

    /** Amount decides, because that is what a wrong category actually distorts. */
    @Test
    fun `larger groups rank above smaller ones`() {
        val big = group(merchant = "Rent", total = 40_000.0)
        val small = group(merchant = "Chai", total = 400.0)

        assertTrue(big.impact > small.impact)
    }

    @Test
    fun `an uncategorised group outranks a settled one of similar size`() {
        val unknown = group(merchant = "PQR Traders", category = ExpenseCategory.Other, total = 5_000.0)
        val known = group(merchant = "Swiggy", category = ExpenseCategory.FoodDining, total = 6_000.0)

        assertTrue(unknown.impact > known.impact)
    }

    /**
     * The counterweight. Lifting unknowns must not bury something genuinely
     * large — a misfiled ₹80,000 transfer distorts more than a ₹500 unknown.
     */
    @Test
    fun `a far larger settled group still outranks a small unknown`() {
        val hugeTransfer = group(merchant = "SELF TRANSFER", total = 80_000.0)
        val smallUnknown = group(merchant = "ABC", category = ExpenseCategory.Other, total = 500.0)

        assertTrue(hugeTransfer.impact > smallUnknown.impact)
    }

    @Test
    fun `the ranking sorts as expected end to end`() {
        val groups = listOf(
            group(merchant = "Chai", total = 400.0),
            group(merchant = "Unknown", category = ExpenseCategory.Other, total = 5_000.0),
            group(merchant = "Rent", total = 40_000.0)
        )

        assertEquals(
            listOf("Rent", "Unknown", "Chai"),
            groups.sortedByDescending { it.impact }.map { it.merchant }
        )
    }
}
