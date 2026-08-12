package com.spendwise.data.ingestion.scoring

import com.spendwise.data.ingestion.model.CurrencyCandidate
import com.spendwise.domain.model.Currency

object CurrencyScorer {

    /** Glyphs. Never letters, so a plain substring test is safe. */
    private val symbolMappings = listOf(
        Currency.INR to listOf("₹"),
        Currency.EUR to listOf("€"),
        Currency.GBP to listOf("£"),
        Currency.JPY to listOf("¥"),
        Currency.SGD to listOf("S$"),
        Currency.CAD to listOf("CA$"),
        Currency.AUD to listOf("A$"),
        Currency.USD to listOf("US$", "$")
    )

    /**
     * Letter codes, matched only as whole words.
     *
     * A substring test here is actively dangerous: "FR" occurs inside "Friends", "RS" inside
     * "hours", "AED" inside "used". A payment screenshot whose rupee glyph the OCR dropped was
     * being labelled Swiss francs purely because its share banner said "Friends receive a...".
     */
    private val codeMappings = listOf(
        Currency.INR to listOf("INR", "RS"),
        Currency.SGD to listOf("SGD"),
        Currency.CAD to listOf("CAD"),
        Currency.AUD to listOf("AUD"),
        Currency.USD to listOf("USD"),
        Currency.EUR to listOf("EUR"),
        Currency.GBP to listOf("GBP"),
        Currency.JPY to listOf("JPY"),
        Currency.AED to listOf("AED"),
        Currency.CHF to listOf("CHF", "FR")
    )

    private val wordCache = mutableMapOf<String, Regex>()

    private fun wordRegex(code: String): Regex =
        wordCache.getOrPut(code) { Regex("""\b${Regex.escape(code)}\b""") }

    fun scoreCandidates(text: String): List<CurrencyCandidate> {
        val upper = text.uppercase()
        val scores = mutableMapOf<Currency, Float>()

        for ((currency, symbols) in symbolMappings) {
            for (symbol in symbols) {
                if (!upper.contains(symbol)) continue
                // A bare "$" is ambiguous across several currencies; a distinctive glyph is not.
                val weight = if (symbol == "$") 0.4f else 0.8f
                scores[currency] = (scores[currency] ?: 0f) + weight
            }
        }

        for ((currency, codes) in codeMappings) {
            for (code in codes) {
                if (!wordRegex(code).containsMatchIn(upper)) continue
                val weight = if (code == "RS") 0.4f else 0.6f
                scores[currency] = (scores[currency] ?: 0f) + weight
            }
        }

        if (scores.isEmpty()) return listOf(CurrencyCandidate(Currency.INR, 0.5f))

        return scores.map { (currency, score) ->
            CurrencyCandidate(currency, score.coerceIn(0f, 1f))
        }
    }

    fun confidenceFrom(candidates: List<CurrencyCandidate>): Float {
        if (candidates.isEmpty()) return 0f
        val sorted = candidates.sortedByDescending { it.score }
        if (sorted.size == 1) return sorted[0].score
        return (sorted[0].score - sorted[1].score).coerceIn(0f, 1f).let { gap ->
            (sorted[0].score * 0.6f + gap * 0.4f).coerceIn(0f, 1f)
        }
    }
}
