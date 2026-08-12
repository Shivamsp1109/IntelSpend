package com.spendwise.data.ingestion.normalizer

/**
 * Pulls the payee out of a structured bank narration.
 *
 * Indian bank statements encode the payee positionally rather than by any
 * property of the text itself:
 *
 * ```
 * WDL TFR  UPI/DR/621427195933/WESTSIDE/HDFC/westside.4/UPI  0097696162090 AT 09006 Bihar  Vet. College Campus Branch
 * └─type─┘ └ch┘└dir┘└──ref───┘└MERCHANT┘└bank┘└──vpa───┘     └───────────── branch boilerplate ─────────────────────┘
 * ```
 *
 * Scoring the segments by length or capitalisation cannot recover that — the
 * transaction-type prefix and the branch address are both longer and more
 * capitalised than the payee, so a "pick the most name-like piece" heuristic
 * reliably returns "Wdl Tfr" or the branch name. Position is the only signal
 * that actually identifies the payee, so match the layout instead of guessing.
 *
 * Returns null for anything that isn't a recognised layout, leaving the caller
 * to fall back to heuristics.
 */
object BankNarrationParser {

    fun merchantFrom(raw: String): String? {
        val cleaned = stripBoilerplate(raw)
        if (cleaned.isBlank()) return null

        for (pattern in CHANNEL_PATTERNS) {
            val candidate = pattern.find(cleaned)?.groupValues?.getOrNull(1) ?: continue
            val merchant = tidy(candidate)
            if (merchant != null) return merchant
        }
        return null
    }

    /**
     * Removes the parts every row on the statement shares: the transaction-type
     * prefix, and the account/branch trailer that starts at the account number.
     */
    private fun stripBoilerplate(raw: String): String =
        raw.replace(LEADING_TYPE, " ")
            .replace(TRAILING_BRANCH, " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun tidy(segment: String): String? {
        val text = segment
            // IMPS puts its own reference in front of the name: RE1-XX288-RAYOLE S
            .replace(REFERENCE_PREFIX, "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '.', ',', '/')

        if (text.count { it.isLetter() } < 2) return null
        // A segment that is only a bank code or channel marker is not a payee.
        if (text.uppercase() in NON_MERCHANT_SEGMENTS) return null
        return text
    }

    private val LEADING_TYPE = Regex("""^\s*(?:WDL|DEP|CSH|CHQ|CASH|TFR)\s+TFR\b""", RegexOption.IGNORE_CASE)

    /**
     * Everything from the account number onward: `0097696162090 AT 09006 Bihar
     * Vet. College Campus Branch`. Anchored on the long account number followed
     * by AT so it can't eat a payee that happens to contain digits.
     */
    private val TRAILING_BRANCH = Regex("""\s*\b\d{8,}\s+AT\b.*$""", RegexOption.IGNORE_CASE)

    private val REFERENCE_PREFIX = Regex("""^[A-Z0-9]{2,}-[A-Z0-9]{2,}-""", RegexOption.IGNORE_CASE)

    /**
     * The payee is the segment immediately after the transaction reference.
     * Ordered most specific first so `UPI/DR/<ref>/` wins over the looser forms.
     */
    private val CHANNEL_PATTERNS = listOf(
        Regex("""\bUPI/(?:DR|CR)/\d+/([^/]+)""", RegexOption.IGNORE_CASE),
        Regex("""\bUPI/\d+/([^/]+)""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:IMPS|NEFT|RTGS|ACH)/\d+/([^/]+)""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:IMPS|NEFT|RTGS|ACH)/[A-Z0-9]+/([^/]+)""", RegexOption.IGNORE_CASE),
        Regex("""\bPOS/(?:\d+/)?([^/]+)""", RegexOption.IGNORE_CASE),
        Regex("""\bATM/(?:\d+/)?([^/]+)""", RegexOption.IGNORE_CASE)
    )

    private val NON_MERCHANT_SEGMENTS = setOf(
        "UPI", "DR", "CR", "NA", "TFR", "WDL", "DEP", "POS", "ATM", "IMPS", "NEFT", "RTGS"
    )
}
