package com.spendwise.data.ingestion.normalizer

import kotlin.math.abs

object AmountNormalizer {
    data class AmountMatch(
        val value: Double,
        val rawText: String,
        val start: Int,
        val end: Int,
        val hasCurrencyToken: Boolean,
        val hasDecimal: Boolean
    )

    private const val CURRENCY_TOKEN =
        "(?:\\u20B9|\\u00E2\\u201A\\u00B9|Rs\\.?|INR|USD|US\\$|S\\$|SGD|CA\\$|CAD|A\\$|AUD|\\$|\\u20AC|\\u00E2\\u201A\\u00AC|EUR|\\u00A3|\\u00C2\\u00A3|GBP|AED|\\u00A5|\\u00C2\\u00A5|JPY|Fr|CHF)"

    private val currencyTokenRegex = Regex(CURRENCY_TOKEN, RegexOption.IGNORE_CASE)

    private val dateLikeRegex = Regex(
        """\b(?:\d{4}[/-]\d{1,2}[/-]\d{1,2}|\d{1,2}[./-]\d{1,2}[./-]\d{2,4}|\d{1,2}[- ]?[A-Za-z]{3,9}[- ]?\d{2,4}|[A-Za-z]{3,9}\s+\d{1,2},?\s+\d{2,4})\b"""
    )

    private val decimalOrGroupedAmountRegex = Regex(
        "(?<![A-Za-z0-9])$CURRENCY_TOKEN?\\s*[-+]?(?:\\d{1,3}(?:[,\\s]\\d{2,3})+(?:\\.\\d{1,2})?|\\d+(?:[.,]\\d{1,2}))(?:\\s*(?:CR|DR))?(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE
    )

    private val integerAmountRegex = Regex(
        "(?<![A-Za-z0-9])$CURRENCY_TOKEN?\\s*[-+]?\\d{1,7}(?:\\s*(?:CR|DR))?(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE
    )

    fun parse(raw: String): Double? {
        val clean = raw
            .replace(currencyTokenRegex, "")
            .replace(Regex("\\b(CR|DR)\\b", RegexOption.IGNORE_CASE), "")
            .replace(" ", "")
            .trim()

        if (clean.isBlank()) return null

        val negative = clean.startsWith("-")
        val unsigned = clean.removePrefix("-").removePrefix("+")
        val lastDot = unsigned.lastIndexOf('.')
        val lastComma = unsigned.lastIndexOf(',')

        val normalized = when {
            lastDot >= 0 && lastComma >= 0 && lastComma > lastDot ->
                unsigned.replace(".", "").replace(',', '.')
            lastDot >= 0 && lastComma >= 0 ->
                unsigned.replace(",", "")
            lastComma >= 0 -> {
                val digitsAfterComma = unsigned.length - lastComma - 1
                if (digitsAfterComma == 2) unsigned.replace(',', '.') else unsigned.replace(",", "")
            }
            else -> unsigned
        }

        val value = normalized.toDoubleOrNull() ?: return null
        return if (negative) -value else value
    }

    fun extractAmounts(text: String, allowInteger: Boolean = true): List<Double> {
        return extractAmountMatches(text, allowInteger).map { it.value }
    }

    fun extractAmountMatches(text: String, allowInteger: Boolean = true): List<AmountMatch> {
        val textWithoutDates = dateLikeRegex.replace(text, " ")
        val decimalMatches = decimalOrGroupedAmountRegex.findAll(textWithoutDates)
            .mapNotNull { it.toAmountMatch() }
            .toList()

        if (decimalMatches.isNotEmpty() || !allowInteger) return decimalMatches

        return integerAmountRegex.findAll(textWithoutDates)
            .mapNotNull { it.toAmountMatch() }
            .toList()
    }

    fun removeAmountTokens(text: String, allowInteger: Boolean = false): String {
        var result = dateLikeRegex.replace(text, " ")
        result = decimalOrGroupedAmountRegex.replace(result, " ")
        if (allowInteger) result = integerAmountRegex.replace(result, " ")
        return result
            .replace(Regex("\\b(CR|DR|DEBIT|CREDIT|BALANCE|CLOSING|OPENING)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ',')
    }

    private fun MatchResult.toAmountMatch(): AmountMatch? {
        val parsed = parse(value)?.let { abs(it) }?.takeIf { it > 0.0 } ?: return null
        val trimmed = value.trim()
        return AmountMatch(
            value = parsed,
            rawText = trimmed,
            start = range.first,
            end = range.last + 1,
            hasCurrencyToken = currencyTokenRegex.containsMatchIn(trimmed),
            hasDecimal = Regex("[.,]\\d{1,2}\\b").containsMatchIn(trimmed)
        )
    }
}
