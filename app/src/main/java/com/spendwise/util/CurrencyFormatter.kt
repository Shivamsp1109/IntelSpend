package com.spendwise.util

import java.text.NumberFormat
import java.util.Locale

object CurrencyFormatter {
    private val formatter = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    fun format(amount: Double): String = formatter.format(amount)
}
