package com.spendwise.data.ingestion.scoring

import com.spendwise.data.ingestion.model.DateCandidate
import com.spendwise.data.ingestion.normalizer.DateNormalizer
import com.spendwise.data.ingestion.normalizer.DayMonthOrder

object DateScorer {
    private val positiveKeywords = listOf("bill date", "invoice date", "transaction date", "txn date", "paid on", "date")
    private val negativeKeywords = listOf("due date", "expiry", "exp date", "valid u", "val u", "birth", "dob", "issued")

    /**
     * [order] resolves all-numeric dates for the document as a whole; pass the value from
     * [DateNormalizer.resolveOrder] so every line in one file is read the same way.
     */
    fun scoreCandidates(
        lines: List<String>,
        order: DayMonthOrder = DayMonthOrder.DAY_FIRST
    ): List<DateCandidate> {
        val candidates = mutableListOf<DateCandidate>()
        for (line in lines) {
            val lower = line.lowercase()
            val token = DateNormalizer.findFirstToken(line) ?: continue
            val parsed = DateNormalizer.parse(token, order) ?: continue

            var score = 0.5f
            positiveKeywords.forEach { kw -> if (lower.contains(kw)) score += 0.25f }
            negativeKeywords.forEach { kw -> if (lower.contains(kw)) score -= 0.35f }
            // An unresolvable day/month order is a real risk of a silently wrong date.
            if (parsed.ambiguous) score -= 0.2f

            candidates.add(DateCandidate(parsed.millis, token, score.coerceIn(0f, 1f)))
        }
        return candidates
    }

    fun confidenceFrom(candidates: List<DateCandidate>): Float {
        if (candidates.isEmpty()) return 0f
        val sorted = candidates.sortedByDescending { it.score }
        if (sorted.size == 1) return sorted[0].score
        // Agreement is evidence: several lines resolving to the same day is a strong signal,
        // whereas two different plausible dates is exactly when a human should look.
        if (sorted[0].value == sorted[1].value) return sorted[0].score.coerceAtLeast(0.9f)
        val gap = (sorted[0].score - sorted[1].score).coerceIn(0f, 1f)
        return (sorted[0].score * 0.6f + gap * 0.4f).coerceIn(0f, 1f)
    }
}
