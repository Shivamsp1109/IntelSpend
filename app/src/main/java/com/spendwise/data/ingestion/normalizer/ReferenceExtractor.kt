package com.spendwise.data.ingestion.normalizer

/**
 * Finds the payment reference — the RRN or UTR — in imported text.
 *
 * This is the one field that survives the journey between documents. A UPI app
 * shows it as "UPI transaction ID"; the bank statement embeds the same digits in
 * its narration (`UPI/DR/621427195933/WESTSIDE/...`). Everything else about
 * those two records differs: the payee is written differently, the timestamps
 * disagree, and the statement may post the payment days later. Matching on this
 * turns duplicate detection from a guess into a lookup.
 *
 * The overriding concern here is not missing a reference — it is inventing one.
 * A wrong reference makes two genuinely different payments look like the same
 * one, and the second gets dropped from the user's records without them ever
 * seeing it. So the rules below are deliberately narrow: a number is only
 * treated as a reference where the surrounding text says it is one, or where it
 * sits in a known positional slot. Loose digit-grabbing would collect account
 * numbers, terminal ids and amounts.
 */
object ReferenceExtractor {

    /**
     * A reference is at least this long. Indian RRNs are 12 digits and UTRs are
     * longer; anything shorter is a terminal id, a batch number or a sequence
     * counter, and matching on those would merge unrelated payments.
     */
    private const val MIN_LENGTH = 8

    private const val MAX_LENGTH = 24

    fun from(text: String): String? {
        if (text.isBlank()) return null

        for (pattern in PATTERNS) {
            val match = pattern.find(text) ?: continue
            // Every pattern below captures the reference in group 1.
            val candidate = match.groupValues.getOrNull(1)?.trim() ?: continue
            if (isPlausible(candidate)) return candidate.uppercase()
        }
        return null
    }

    /**
     * Rejects values that are the right shape but cannot be a reference.
     *
     * A run of identical digits, or a plain ascending sequence, is far more
     * likely to be a mask or a placeholder than a real one.
     */
    private fun isPlausible(candidate: String): Boolean {
        if (candidate.length !in MIN_LENGTH..MAX_LENGTH) return false
        if (!candidate.all { it.isLetterOrDigit() }) return false
        // A reference is mostly digits; a long word is not one.
        if (candidate.count { it.isDigit() } < MIN_LENGTH) return false
        if (candidate.toSet().size == 1) return false
        return true
    }

    /**
     * Ordered most trustworthy first.
     *
     * The labelled forms come first because the surrounding words confirm what
     * the number is. The positional forms follow: in a bank narration the
     * reference always sits between the channel and the payee, so its slot
     * identifies it even without a label.
     */
    private val PATTERNS = listOf(
        // "UTR: 123456789012", "UPI Transaction ID 123456789012", "Ref No. 1234..."
        Regex(
            """\b(?:UTR|RRN|UPI\s*(?:transaction\s*)?(?:ID|Ref)|Transaction\s*(?:ID|Ref(?:erence)?)|Ref(?:erence)?\s*(?:No\.?|Number|ID)?)\s*[:#-]?\s*([A-Z0-9]{8,24})\b""",
            setOf(RegexOption.IGNORE_CASE)
        ),
        // Bank narration, positional: UPI/DR/<reference>/PAYEE
        Regex("""\bUPI/(?:DR|CR)/(\d{8,24})/""", RegexOption.IGNORE_CASE),
        Regex("""\bUPI/(\d{8,24})/""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:IMPS|NEFT|RTGS|ACH)/(\d{8,24})/""", RegexOption.IGNORE_CASE)
    )
}
