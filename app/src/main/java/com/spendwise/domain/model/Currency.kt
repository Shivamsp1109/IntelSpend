package com.spendwise.domain.model

/**
 * ISO 4217-based currency enum covering the 10 most widely used currencies.
 * Used by both [Expense] and [Income].
 *
 * [minorUnitDigits] is the ISO 4217 exponent — how many decimal places the
 * currency actually has. It is carried here rather than assumed to be two by
 * whatever needs it, because [MoneyAmount] stores whole minor units and a
 * hardcoded 100 would silently inflate every yen figure by a hundredfold.
 */
enum class Currency(
    val code: String,
    val symbol: String,
    val displayName: String,
    val minorUnitDigits: Int = 2
) {
    INR("INR", "₹", "Indian Rupee"),
    USD("USD", "$", "US Dollar"),
    EUR("EUR", "€", "Euro"),
    GBP("GBP", "£", "British Pound"),
    JPY("JPY", "¥", "Japanese Yen", minorUnitDigits = 0),
    AED("AED", "د.إ", "UAE Dirham"),
    SGD("SGD", "S$", "Singapore Dollar"),
    CAD("CAD", "CA$", "Canadian Dollar"),
    AUD("AUD", "A$", "Australian Dollar"),
    CHF("CHF", "Fr", "Swiss Franc");

    companion object {
        fun fromCode(code: String): Currency =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: INR
    }
}
