package com.spendwise.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The type exists to stop money drifting, so these tests are about exactness
 * rather than behaviour. Each one pins a case where a `Double` would be wrong
 * by an amount small enough that nothing would notice until a total disagreed
 * with the rows that made it.
 */
class MoneyAmountTest {

    private fun inr(decimal: String) = MoneyAmount.fromDecimalString(decimal, Currency.INR)

    @Test
    fun `adding tenths is exact`() {
        // 0.1 + 0.2 == 0.30000000000000004 in binary floating point.
        val total = inr("0.10") + inr("0.20")

        assertEquals(30L, total.minorUnits)
        assertEquals("0.30", total.toDecimalString())
        assertEquals(inr("0.30"), total)
    }

    @Test
    fun `a long run of small amounts sums exactly`() {
        // A hundred ₹0.07 charges. In Double this lands at 7.000000000000005
        // and compares unequal to 7.0 — the classic silent reconciliation bug.
        val total = MoneyAmount.sum(List(100) { inr("0.07") }, Currency.INR)

        assertEquals(700L, total.minorUnits)
        assertEquals("7.00", total.toDecimalString())
    }

    @Test
    fun `equality is real equality`() {
        assertEquals(inr("199.99"), inr("199.99"))
        assertTrue(inr("199.99") == inr("199.99"))
    }

    @Test
    fun `a legacy Double column reads back as the figure the user typed`() {
        // 199.99 as a Double is really 199.99000000000000909... Reading it
        // through the shortest round-tripping decimal gives back 199.99.
        val amount = MoneyAmount.fromMajorDouble(199.99, Currency.INR)

        assertEquals(19_999L, amount.minorUnits)
        assertEquals("199.99", amount.toDecimalString())
    }

    @Test
    fun `round trips through the legacy Double boundary unchanged`() {
        listOf("0.01", "199.99", "99999999.99", "-450.50").forEach { decimal ->
            val original = inr(decimal)
            val roundTripped = MoneyAmount.fromMajorDouble(
                original.toMajorDouble(),
                Currency.INR
            )
            assertEquals(original, roundTripped)
        }
    }

    @Test
    fun `a currency with no minor unit is not inflated a hundredfold`() {
        // ¥5000 is 5000 yen, not 500,000. A hardcoded 100 would be wrong here
        // in a way that looks like a plausible number.
        val yen = MoneyAmount.fromDecimalString("5000", Currency.JPY)

        assertEquals(5_000L, yen.minorUnits)
        assertEquals("5000", yen.toDecimalString())
    }

    @Test
    fun `mixing currencies throws rather than picking one`() {
        val rupees = MoneyAmount.fromDecimalString("100", Currency.INR)
        val dollars = MoneyAmount.fromDecimalString("100", Currency.USD)

        assertThrows(IllegalArgumentException::class.java) { rupees + dollars }
        assertThrows(IllegalArgumentException::class.java) { rupees > dollars }
    }

    @Test
    fun `scaling rounds half-even so a long projection does not drift upward`() {
        // Both land exactly on a half-paisa. Half-up would round both away from
        // zero; half-even sends one each way, so the bias cancels instead of
        // compounding over a three-hundred-month projection.
        assertEquals(2L, MoneyAmount(5L, Currency.INR).scaledBy(0.5).minorUnits)
        assertEquals(4L, MoneyAmount(7L, Currency.INR).scaledBy(0.5).minorUnits)
    }

    @Test
    fun `reading a figure finer than the currency rounds once, at the boundary`() {
        assertEquals(1_00L, inr("0.995").minorUnits)
        assertEquals(1_00L, inr("1.004").minorUnits)
    }

    @Test
    fun `a ratio against nothing has no answer`() {
        // "What share of no income did you save" is unanswerable; zero would be
        // a claim about behaviour the data does not support.
        assertNull(inr("500").ratioTo(MoneyAmount.zero(Currency.INR)))
        assertEquals(0.25, inr("250").ratioTo(inr("1000"))!!, 0.0001)
    }

    @Test
    fun `overflow is refused rather than silently wrapping`() {
        val huge = MoneyAmount(Long.MAX_VALUE, Currency.INR)

        assertThrows(ArithmeticException::class.java) { huge + inr("0.01") }
    }

    @Test
    fun `negatives survive subtraction and coercion`() {
        val deficit = inr("100") - inr("450.50")

        assertEquals(-35_050L, deficit.minorUnits)
        assertEquals("-350.50", deficit.toDecimalString())
        assertTrue(deficit.isNegative)
        assertTrue(deficit.coerceAtLeastZero().isZero)
    }
}
