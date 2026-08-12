package com.spendwise.data.ingestion.scoring

import com.spendwise.data.ingestion.model.AmountCandidate
import com.spendwise.data.ingestion.normalizer.AmountNormalizer

object AmountScorer {

    private val positiveKeywords = listOf(
        "total paid" to 0.35f, "grand total" to 0.35f, "amount paid" to 0.30f,
        "amount payable" to 0.30f, "net amount" to 0.25f, "transaction amount" to 0.25f,
        "debit amount" to 0.20f, "paid" to 0.20f, "you paid" to 0.25f,
        "sent" to 0.15f, "payment of" to 0.20f, "total" to 0.15f
    )

    private val negativeKeywords = listOf(
        "subtotal" to 0.25f, "sub total" to 0.25f, "tax" to 0.25f, "gst" to 0.25f,
        "cgst" to 0.25f, "sgst" to 0.25f, "discount" to 0.30f, "balance" to 0.40f,
        "available balance" to 0.40f, "cashback" to 0.25f, "reward" to 0.25f,
        "mrp" to 0.15f, "change" to 0.20f, "qty" to 0.30f, "item" to 0.20f,
        "transaction id" to 0.55f, "txn id" to 0.55f, "reference" to 0.50f,
        "ref no" to 0.50f, "utr" to 0.55f, "rrn" to 0.55f, "approval" to 0.45f,
        "auth" to 0.35f, "invoice no" to 0.45f, "bill no" to 0.45f,
        "hsn" to 0.50f, "code" to 0.35f, "account" to 0.35f, "card" to 0.25f,
        "mobile" to 0.45f, "phone" to 0.45f, "limit" to 0.35f
    )

    /**
     * A split bill prints the table total and this user's own share together, and only the
     * share is their expense. The share is always the smaller figure and always the less
     * prominently typeset one, so it has to be identified by wording.
     *
     * Detected structurally rather than by listing phrases: the line addresses the reader in
     * the second person *and* names a payment. That covers "paid by you", "Your share",
     * "You pay", "Your portion", "You owe" and anything else phrased the same way, which a
     * fixed phrase list does not.
     */
    private val SECOND_PERSON = Regex("""\b(you|your|yours)\b""")
    private val PAYMENT_WORD = Regex(
        """\b(paid|pay|pays|paying|share|owe|owes|owed|portion|split|contribution|due)\b"""
    )
    private const val PERSONAL_SHARE_WEIGHT = 0.45f

    private fun isPersonalShare(lower: String): Boolean =
        SECOND_PERSON.containsMatchIn(lower) && PAYMENT_WORD.containsMatchIn(lower)
    fun scoreCandidates(lines: List<String>, allowInteger: Boolean = true): List<AmountCandidate> {
        return lines.flatMap { line ->
            AmountNormalizer.extractAmountMatches(line, allowInteger = allowInteger).map { match ->
                scoreCandidate(line, match)
            }
        }
    }

    /**
     * Returns a raw, deliberately unclamped score.
     *
     * Clamping here would saturate: on a split bill both the total and the user's share reach
     * 1.0, the ordering between them is lost, and `maxByOrNull` silently falls back to
     * whichever appears first — the total. Callers rank on this value and clamp only when
     * turning it into a confidence.
     */
    fun scoreCandidate(line: String, match: AmountNormalizer.AmountMatch): AmountCandidate {
        val lower = line.lowercase()
        var score = 0.5f
        positiveKeywords.forEach { (kw, w) -> if (lower.contains(kw)) score += w }
        negativeKeywords.forEach { (kw, w) -> if (lower.contains(kw)) score -= w }
        if (isPersonalShare(lower)) score += PERSONAL_SHARE_WEIGHT
        if (match.hasCurrencyToken) score += 0.25f
        if (match.hasDecimal) score += 0.10f
        if (match.rawText.filter { it.isDigit() }.length >= 6 && !match.hasDecimal) score -= 0.35f
        return AmountCandidate(match.value, match.rawText, line, score)
    }

    fun confidenceFrom(candidates: List<AmountCandidate>): Float {
        if (candidates.isEmpty()) return 0f
        val sorted = candidates.sortedByDescending { it.score }
        if (sorted.size == 1) return sorted[0].score.coerceIn(0f, 1f)
        val gap = (sorted[0].score - sorted[1].score).coerceIn(0f, 1f)
        return (sorted[0].score.coerceIn(0f, 1f) * 0.6f + gap * 0.4f).coerceIn(0f, 1f)
    }
}
