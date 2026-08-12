package com.spendwise.util

import com.spendwise.domain.model.Currency
import java.text.NumberFormat
import java.util.Locale

object CurrencyFormatter {
    private val formatter = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    private val amountFormatter = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    /** Formats as INR. Prefer [format] with an explicit [Currency] for non-INR amounts. */
    fun format(amount: Double): String = formatter.format(amount)

    fun format(amount: Double, currency: Currency): String =
        "${currency.symbol}${amountFormatter.format(amount)}"
}
