package com.spendwise.data.ingestion.normalizer

object MerchantNormalizer {
    private const val POSITION_PENALTY = 5
    private const val UPPERCASE_BONUS = 6
    private const val MAX_NAME_LETTERS = 18

    /**
     * A long alphanumeric run that contains at least one digit — a UTR, RRN or transaction id.
     *
     * The digit requirement is the whole point. Matching any 8-plus character run case
     * insensitively also deletes ordinary merchant names of that length, so RELIANCE FRESH
     * became "Fresh", STARBUCKS STORE became "Store" and FLIPKART INTERNET became "Order".
     */
    private val REFERENCE_CODE = Regex(
        """\b(?=[A-Za-z0-9]{8,}\b)[A-Za-z0-9]*[0-9][A-Za-z0-9]*\b"""
    )

    private fun isMostlyUpperCase(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        if (letters < 3) return false
        return text.count { it.isUpperCase() }.toFloat() / letters >= 0.8f
    }

    fun normalize(raw: String): String {
        // A payment collected by an aggregator names the aggregator first and
        // the shop second, so "PAYTM*SWIGGY" would otherwise display as Paytm —
        // which is true of half the user's transactions and identifies none of
        // them.
        var clean = MerchantNoise.stripProcessorPrefix(raw.trim())

        // Transaction-type words that survived positional parsing, e.g. a
        // narration ending "... WDL TFR" rather than beginning with it.
        clean = MerchantNoise.trimStructuralEdges(clean)

        // Remove common suffixes
        val suffixes = listOf(
            " PVT LTD", " PVT. LTD.", " PRIVATE LIMITED", " LTD", " LIMITED", 
            " INC", " LLC", " INDIA", " CORPORATION", " CORP"
        )
        
        for (suffix in suffixes) {
            if (clean.uppercase().endsWith(suffix)) {
                clean = clean.substring(0, clean.length - suffix.length).trim()
            }
        }
        
        // Remove trailing asterisks or random punctuation at ends
        clean = clean.trimEnd('*', '.', ',', '-', ' ')
        
        // Convert to Title Case
        return clean.split(" ").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    fun normalizeDescription(raw: String): String {
        // A structured bank narration encodes the payee by position, so read it
        // there rather than scoring segments — scoring picks the transaction-type
        // prefix or the branch address, both of which outrank the real payee.
        BankNarrationParser.merchantFrom(raw)?.let { return normalize(it) }

        val separators = Regex("[/|*:_-]+")
        val noise = Regex(
            "\\b(UPI|POS|ATM|IMPS|NEFT|RTGS|ACH|VPA|PAYTM|GPAY|PHONEPE|RAZORPAY|PAYMENT|TRANSFER|DEBIT|CREDIT|REFUND|REVERSAL|CASHBACK|DR|CR|TO|FROM|REF|REFERENCE|TXN|UTR|RRN|ID|NO|NARRATION)\\b",
            RegexOption.IGNORE_CASE
        )

        val pieces = raw
            .split(separators)
            .mapIndexed { index, part ->
                index to part.replace(Regex("\\b[\\w.+-]+@[\\w.-]+\\b"), " ")
                    .replace(REFERENCE_CODE, " ")
                    .replace(noise, " ")
                    .replace(Regex("\\b\\d{3,}\\b"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim(' ', '-', ',', '.')
            }
            .filter { (_, part) -> part.count { it.isLetter() } >= 3 }

        val selected = pieces
            .maxByOrNull { (index, part) ->
                // Capped: a trading name has a bounded length, so past this point extra letters
                // mean prose, not a name. Uncapped, the note "consulting invoice for December"
                // outscores the payee "GLOBEX SOLUTIONS" on sheer length.
                var score = part.count { it.isLetter() }.coerceAtMost(MAX_NAME_LETTERS)
                if (Regex("[A-Za-z]{4,}").containsMatchIn(part)) score += 4
                if (part.length in 4..40) score += 3
                // Payee names are printed in caps; anything the payer typed is lower case. This
                // is what picks BESCOM over "electricity bill" in "UPI/BESCOM/electricity bill".
                if (isMostlyUpperCase(part)) score += UPPERCASE_BONUS
                // Bank narrations read CHANNEL/MERCHANT/LOCATION, so the merchant is the first
                // surviving piece. Without this, "POS/SWIGGY/BANGALORE" titles as "Bangalore"
                // because the city name simply has more letters.
                score - index * POSITION_PENALTY
            }
            ?.second
            ?: raw

        return normalize(selected)
    }
}
