package com.spendwise.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * An amount of money, held as a whole number of the currency's smallest unit.
 *
 * Every monetary column this app already has is a `Double`, and for recording
 * what a user typed that is survivable. For an engine it is not: `0.1 + 0.2`
 * is not `0.3` in binary floating point, so a column of figures that each look
 * right sums to something a user can see is wrong, and two amounts that should
 * match compare unequal. Neither failure announces itself — the number just
 * drifts, and a financial assessment built on it is quietly incorrect.
 *
 * Whole minor units remove the problem rather than manage it. There is no
 * fraction to lose, addition and subtraction are exact, and equality means
 * equality. Rounding happens once, deliberately, at the boundary where a
 * decimal figure enters — never repeatedly and invisibly through a calculation.
 *
 * Currency travels with the amount because an amount without one cannot be
 * added to anything safely, and this app has no exchange-rate source. Mixing
 * currencies throws rather than picking one, which is the only honest option
 * when the right answer is unknowable.
 */
data class MoneyAmount(val minorUnits: Long, val currency: Currency) {

    val isZero: Boolean get() = minorUnits == 0L
    val isNegative: Boolean get() = minorUnits < 0L
    val isPositive: Boolean get() = minorUnits > 0L

    operator fun plus(other: MoneyAmount): MoneyAmount {
        requireSameCurrency(other)
        return MoneyAmount(Math.addExact(minorUnits, other.minorUnits), currency)
    }

    operator fun minus(other: MoneyAmount): MoneyAmount {
        requireSameCurrency(other)
        return MoneyAmount(Math.subtractExact(minorUnits, other.minorUnits), currency)
    }

    operator fun times(count: Int): MoneyAmount =
        MoneyAmount(Math.multiplyExact(minorUnits, count.toLong()), currency)

    operator fun unaryMinus(): MoneyAmount = MoneyAmount(-minorUnits, currency)

    operator fun compareTo(other: MoneyAmount): Int {
        requireSameCurrency(other)
        return minorUnits.compareTo(other.minorUnits)
    }

    fun coerceAtLeastZero(): MoneyAmount =
        if (isNegative) MoneyAmount(0L, currency) else this

    /**
     * Scales by a rate — a growth assumption, a share, an interest rate.
     *
     * The only operation here that has to round, so it says how: half-even,
     * which unlike half-up does not drift upward across a long run of
     * calculations. A projection over three hundred months compounds that bias
     * into a figure that is visibly wrong.
     */
    fun scaledBy(factor: Double): MoneyAmount {
        require(factor.isFinite()) { "Cannot scale money by $factor." }
        val scaled = BigDecimal.valueOf(minorUnits)
            .multiply(BigDecimal.valueOf(factor))
            .setScale(0, RoundingMode.HALF_EVEN)
        return MoneyAmount(scaled.toLong(), currency)
    }

    /**
     * This amount as a fraction of another — a savings rate, a debt ratio.
     *
     * Null rather than zero or infinity when the denominator is zero: "what
     * share of no income did you save" has no answer, and any number returned
     * there would be a claim the data does not support.
     */
    fun ratioTo(other: MoneyAmount): Double? {
        requireSameCurrency(other)
        if (other.minorUnits == 0L) return null
        return minorUnits.toDouble() / other.minorUnits.toDouble()
    }

    /** The decimal form, for the `DECIMAL` columns the engine's tables use. */
    fun toDecimalString(): String = BigDecimal.valueOf(minorUnits)
        .movePointLeft(currency.minorUnitDigits)
        .toPlainString()

    /**
     * The legacy `Double` form.
     *
     * Exists only for the boundary with the tables and models that predate this
     * type. Never take a figure back out through here to keep calculating with
     * it — that reintroduces exactly the imprecision the type exists to avoid.
     */
    fun toMajorDouble(): Double = toDecimalString().toDouble()

    override fun toString(): String = "${currency.code} ${toDecimalString()}"

    private fun requireSameCurrency(other: MoneyAmount) {
        require(currency == other.currency) {
            "Cannot combine ${currency.code} with ${other.currency.code}; " +
                "this app has no exchange rate source."
        }
    }

    companion object {

        fun zero(currency: Currency) = MoneyAmount(0L, currency)

        /**
         * Reads a decimal figure into minor units.
         *
         * The rounding boundary. A figure with more decimal places than the
         * currency has is rounded here, once, half-even — rather than carried
         * along as a fraction that rounds differently every time something
         * formats it.
         */
        fun fromDecimalString(value: String, currency: Currency): MoneyAmount =
            fromBigDecimal(BigDecimal(value.trim()), currency)

        /**
         * Reads a legacy `Double` column into minor units.
         *
         * `BigDecimal.valueOf` goes through the shortest decimal string that
         * round-trips the double, so a stored 199.99 reads as 199.99 rather
         * than the 199.99000000000000909 its binary form literally holds.
         */
        fun fromMajorDouble(value: Double, currency: Currency): MoneyAmount {
            require(value.isFinite()) { "Cannot read $value as money." }
            return fromBigDecimal(BigDecimal.valueOf(value), currency)
        }

        private fun fromBigDecimal(value: BigDecimal, currency: Currency): MoneyAmount =
            MoneyAmount(
                value.movePointRight(currency.minorUnitDigits)
                    .setScale(0, RoundingMode.HALF_EVEN)
                    .toLong(),
                currency
            )

        /** Sums amounts that must already agree on currency; zero when empty. */
        fun sum(amounts: List<MoneyAmount>, currency: Currency): MoneyAmount =
            amounts.fold(zero(currency)) { running, next -> running + next }
    }
}
