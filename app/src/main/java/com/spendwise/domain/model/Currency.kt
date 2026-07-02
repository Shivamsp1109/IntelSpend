package com.spendwise.domain.model

/**
 * ISO 4217-based currency enum covering the 10 most widely used currencies.
 * Used by both [Expense] and [Income].
 */
enum class Currency(val code: String, val symbol: String, val displayName: String) {
    INR("INR", "₹", "Indian Rupee"),
    USD("USD", "$", "US Dollar"),
    EUR("EUR", "€", "Euro"),
    GBP("GBP", "£", "British Pound"),
    JPY("JPY", "¥", "Japanese Yen"),
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
