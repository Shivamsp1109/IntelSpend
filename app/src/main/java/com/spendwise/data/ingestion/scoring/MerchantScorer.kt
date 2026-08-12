package com.spendwise.data.ingestion.scoring

import com.spendwise.data.ingestion.model.MerchantCandidate
import com.spendwise.data.ingestion.ocr.OcrLine

object MerchantScorer {
    private val skipLabels = listOf("gstin", "dl.no", "doctor", "patient", "bill no", "date", "time", "hsn", "total", "amount", "cash", "balance")

    /**
     * Fixed legal text printed on every receipt. It is often near the top or in large type, so
     * position and font size alone will happily pick "Subject to Bangalore Jurisdiction" as the
     * merchant.
     */
    private val boilerplate = Regex(
        """\b(note|subject to|jurisdiction|e ?& ?o\.?e|terms|conditions|thank you|thanks|visit again|come again|goods once sold|no exchange|no refund|not returnable|computer generated|declaration|authorised signatory|customer copy|merchant copy)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Words that appear in trading names, as a positive counterweight to the above. */
    private val businessWords = Regex(
        """\b(bazaar|bazar|drug|drugs|pharma|pharmacy|medical|medicals|chemist|store|stores|mart|supermarket|hypermarket|restaurant|hotel|cafe|bakery|counter|traders|trading|enterprises|enterprise|services|agencies|industries|foods|retail|provisions|and sons|& sons)\b""",
        RegexOption.IGNORE_CASE
    )

    private fun contentAdjustment(text: String): Float {
        var delta = 0f
        if (boilerplate.containsMatchIn(text)) delta -= 0.5f
        if (businessWords.containsMatchIn(text)) delta += 0.2f
        return delta
    }

    fun scoreCandidates(ocrLines: List<OcrLine>): List<MerchantCandidate> {
        if (ocrLines.isEmpty()) return emptyList()

        val maxTop = ocrLines.maxOfOrNull { it.top + it.height } ?: 1000
        val topRegionCutoff = maxTop * 0.25
        val maxHeight = ocrLines.maxOfOrNull { it.height } ?: 20

        return ocrLines.map { line ->
            var score = 0.5f

            // 1. Position score (prefer top 25%)
            if (line.top <= topRegionCutoff) {
                score += 0.25f
            } else {
                val ratio = line.top.toFloat() / maxTop
                score -= (ratio * 0.4f)
            }

            // 2. Font size score (prefer taller text)
            if (maxHeight > 0) {
                val sizeRatio = line.height.toFloat() / maxHeight
                score += (sizeRatio * 0.35f)
            }

            // 3. Skip label / colon penalty
            val lower = line.text.lowercase()
            if (line.text.contains(":")) {
                score -= 0.3f
            }
            if (skipLabels.any { lower.contains(it) }) {
                score -= 0.4f
            }

            // 4. Character composition
            val letters = line.text.count { it.isLetter() }
            if (line.text.isNotEmpty() && letters.toFloat() / line.text.length < 0.6f) {
                score -= 0.3f
            }
            if (line.text.length <= 2) {
                score -= 0.4f
            }

            score += contentAdjustment(line.text)

            MerchantCandidate(line.text.trim(), score.coerceIn(0f, 1f))
        }
    }

    fun scoreRawCandidates(lines: List<String>): List<MerchantCandidate> {
        return lines.mapIndexed { index, line ->
            var score = 0.5f

            // 1. Position score (first few lines)
            if (index < 3) {
                score += 0.25f
            } else {
                score -= (index * 0.05f)
            }

            // 2. Skip label / colon penalty
            val lower = line.lowercase()
            if (line.contains(":")) {
                score -= 0.3f
            }
            if (skipLabels.any { lower.contains(it) }) {
                score -= 0.4f
            }

            // 3. Uppercase ratio
            val upperCount = line.count { it.isUpperCase() }
            if (line.isNotEmpty() && upperCount.toFloat() / line.length > 0.4f) {
                score += 0.15f
            }

            // 4. Letters composition
            val letters = line.count { it.isLetter() }
            if (line.isNotEmpty() && letters.toFloat() / line.length < 0.6f) {
                score -= 0.3f
            }
            if (line.length <= 2) {
                score -= 0.4f
            }

            score += contentAdjustment(line)

            MerchantCandidate(line.trim(), score.coerceIn(0f, 1f))
        }
    }

    fun confidenceFrom(candidates: List<MerchantCandidate>): Float {
        if (candidates.isEmpty()) return 0f
        val sorted = candidates.sortedByDescending { it.score }
        if (sorted.size == 1) return sorted[0].score
        return (sorted[0].score - sorted[1].score).coerceIn(0f, 1f).let { gap ->
            (sorted[0].score * 0.6f + gap * 0.4f).coerceIn(0f, 1f)
        }
    }
}
