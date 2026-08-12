package com.spendwise.data.ingestion.normalizer

import com.spendwise.domain.model.Currency

object CurrencyNormalizer {
    fun detect(raw: String): Currency? {
        val upper = raw.trim().uppercase()
        return when {
            upper.contains("\u20B9") ||
                upper.contains("\u00E2\u201A\u00B9") ||
                Regex("\\b(RS\\.?|INR)\\b").containsMatchIn(upper) -> Currency.INR
            upper.contains("S$") || Regex("\\bSGD\\b").containsMatchIn(upper) -> Currency.SGD
            upper.contains("CA$") || Regex("\\bCAD\\b").containsMatchIn(upper) -> Currency.CAD
            upper.contains("A$") || Regex("\\bAUD\\b").containsMatchIn(upper) -> Currency.AUD
            Regex("\\bUSD\\b").containsMatchIn(upper) || upper.contains("US$") || upper.contains("$") -> Currency.USD
            upper.contains("\u20AC") ||
                upper.contains("\u00E2\u201A\u00AC") ||
                Regex("\\bEUR\\b").containsMatchIn(upper) -> Currency.EUR
            upper.contains("\u00A3") ||
                upper.contains("\u00C2\u00A3") ||
                Regex("\\bGBP\\b").containsMatchIn(upper) -> Currency.GBP
            upper.contains("\u00A5") ||
                upper.contains("\u00C2\u00A5") ||
                Regex("\\bJPY\\b").containsMatchIn(upper) -> Currency.JPY
            Regex("\\bAED\\b").containsMatchIn(upper) -> Currency.AED
            Regex("\\b(CHF|FR)\\b").containsMatchIn(upper) -> Currency.CHF
            else -> null
        }
    }

    fun normalize(raw: String, defaultCurrency: Currency = Currency.INR): Currency {
        return detect(raw) ?: defaultCurrency
    }
}
