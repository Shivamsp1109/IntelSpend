package com.spendwise.data.ingestion.extractor

import com.spendwise.data.ingestion.model.RawTransaction
import com.spendwise.data.ingestion.model.TransactionType
import com.spendwise.data.ingestion.model.FieldConfidence
import com.spendwise.data.ingestion.ocr.OcrLine
import com.spendwise.data.ingestion.ocr.OcrResult
import com.spendwise.data.ingestion.scoring.AmountScorer
import com.spendwise.data.ingestion.scoring.CurrencyScorer
import com.spendwise.data.ingestion.scoring.DateScorer
import com.spendwise.data.ingestion.scoring.MerchantScorer
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseSource
import javax.inject.Inject

class ScreenshotExtractor @Inject constructor() {
    fun extract(ocrResult: OcrResult): List<RawTransaction> {
        val clockRegex = Regex("""^\d{1,2}:\d{2}(\s*[AP]M)?$""", RegexOption.IGNORE_CASE)
        val lines = ocrResult.lines.ifEmpty {
            ocrResult.fullText
                .split("\n")
                .mapIndexed { index, text -> OcrLine(text = text.trim(), top = index * 24, height = 20) }
                .filter { it.text.isNotBlank() }
        }.filterNot { clockRegex.matches(it.text.trim()) }
        if (lines.isEmpty()) return emptyList()

        val rawLinesText = lines.map { it.text }

        // 1. Amount Candidates
        val amountCandidates = AmountScorer.scoreCandidates(rawLinesText)
        val maxHeight = lines.maxOfOrNull { it.height }?.coerceAtLeast(1) ?: 1
        val boostedAmounts = amountCandidates.map { candidate ->
            val matchingLine = lines.firstOrNull { it.text == candidate.line }
            // Typography is a weak hint, so it is weighted below the wording. The headline
            // figure on a split bill is the table total, not what this user paid.
            val sizeBoost = matchingLine?.let { (it.height.toFloat() / maxHeight) * SIZE_BOOST } ?: 0f
            candidate.copy(score = candidate.score + sizeBoost)
        }
        val bestAmount = boostedAmounts.maxByOrNull { it.score }?.value ?: 0.0
        val bestAmountLine = boostedAmounts.maxByOrNull { it.score }?.line
        val amountConfidence = AmountScorer.confidenceFrom(boostedAmounts)
        if (bestAmount == 0.0) return emptyList()

        // 2. Merchant Candidates
        val merchantCandidates = MerchantScorer.scoreCandidates(lines)
        val merchant = findMerchant(lines, bestAmountLine)
            ?: merchantCandidates.maxByOrNull { it.score }?.value
        val merchantConfidence = if (merchant != null) {
            MerchantScorer.confidenceFrom(merchantCandidates).coerceAtLeast(0.75f)
        } else {
            MerchantScorer.confidenceFrom(merchantCandidates)
        }

        // 3. Date Candidates
        val order = com.spendwise.data.ingestion.normalizer.DateNormalizer.resolveOrder(rawLinesText)
        val dateCandidates = DateScorer.scoreCandidates(rawLinesText, order)
        val dateMillis = dateCandidates.maxByOrNull { it.score }?.value
        val dateConfidence = DateScorer.confidenceFrom(dateCandidates)

        // 4. Currency Candidates
        val currencyCandidates = CurrencyScorer.scoreCandidates(ocrResult.fullText)
        val currency = currencyCandidates.maxByOrNull { it.score }?.value ?: Currency.INR
        val currencyConfidence = CurrencyScorer.confidenceFrom(currencyCandidates)

        val overallConfidence = (merchantConfidence + dateConfidence + amountConfidence + currencyConfidence) / 4f
        val fieldConfidence = FieldConfidence(
            title = merchantConfidence,
            amount = amountConfidence,
            date = dateConfidence,
            merchant = merchantConfidence,
            currency = currencyConfidence,
            category = 0.8f
        )

        val title = merchant ?: "Screenshot Import"
        return listOf(
            RawTransaction(
                title = title,
                amount = bestAmount,
                date = dateMillis ?: System.currentTimeMillis(),
                merchant = merchant,
                currency = currency,
                type = TransactionType.DEBIT,
                source = ExpenseSource.SCREENSHOT,
                confidence = overallConfidence,
                fieldConfidence = fieldConfidence
            )
        )
    }

    private fun findMerchant(lines: List<OcrLine>, amountLine: String?): String? {
        val sortedLines = lines.sortedBy { it.top }
        val payeeLabels = listOf("banking name", "paid to", "paid for", "to", "payee name", "receiver", "merchant")

        for ((index, line) in sortedLines.withIndex()) {
            val text = line.text.trim()
            val lower = text.lowercase().trimEnd(':')
            val inlineLabel = payeeLabels.firstOrNull { lower.startsWith("$it:") || lower.startsWith("$it ") }
            if (inlineLabel != null) {
                val inlineValue = text.substringAfter(':', "").ifBlank {
                    text.substring(inlineLabel.length).trim()
                }.trim()
                if (isMerchantLike(inlineValue)) return inlineValue
            }
            if (payeeLabels.any { lower == it }) {
                sortedLines.drop(index + 1).firstOrNull { isMerchantLike(it.text) }?.let { return it.text.trim() }
            }
        }

        // Rank rather than take the nearest. Payment screens put a status line ("Bill cleared")
        // directly above the amount, so the closest candidate is routinely the wrong one — the
        // merchant is further up but set in much larger type.
        val amountIndex = amountLine?.let { line -> sortedLines.indexOfFirst { it.text == line } } ?: -1
        if (amountIndex > 0) {
            bestScoring(sortedLines, sortedLines.take(amountIndex))?.let { return it }
        }

        return bestScoring(sortedLines, sortedLines)
    }

    /**
     * Highest-scoring merchant-like line in [candidates], scored against the whole page so
     * position and relative type size stay meaningful.
     */
    private fun bestScoring(all: List<OcrLine>, candidates: List<OcrLine>): String? {
        val eligible = candidates.filter { isMerchantLike(it.text) }
        if (eligible.isEmpty()) return null
        val scored = MerchantScorer.scoreCandidates(all)
        return all.zip(scored)
            .filter { (line, _) -> eligible.any { it === line } }
            .maxByOrNull { (_, candidate) -> candidate.score }
            ?.second?.value?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun isMerchantLike(raw: String): Boolean {
        val text = raw.trim()
        if (text.length < 3) return false
        if (AmountScorer.scoreCandidates(listOf(text)).any { it.score >= 0.45f }) return false
        val lower = text.lowercase()
        if (REFERENCE_PHRASES.any { lower.contains(it) }) return false
        if (isStatusLine(lower)) return false
        // Matched whole so they cannot fire inside a real name — "new" sits inside "Newport",
        // "share" inside "Shareef".
        if (lower.trimEnd(':', '?', '!', '.') in UI_CHROME) return false
        if (com.spendwise.data.ingestion.normalizer.DateNormalizer.findFirstToken(text) != null) return false
        return text.count { it.isLetter() } >= 3
    }

    /**
     * True when every word is drawn from status vocabulary — "Bill cleared", "Money Sent",
     * "Order delivered", "Payment Successful".
     *
     * Built from vocabulary rather than a list of banner strings so it holds for apps whose
     * exact wording we have never seen. A trading name reliably contains at least one word
     * that is not payment vocabulary, which is what keeps "Reliance Digital" and "Cult
     * Fitness" out of it.
     */
    private fun isStatusLine(lower: String): Boolean {
        val words = lower.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        return words.all { it in STATUS_VOCABULARY || it in FILLER_WORDS }
    }

    private companion object {
        const val SIZE_BOOST = 0.25f

        /** Identifier labels, which are never a merchant no matter what surrounds them. */
        val REFERENCE_PHRASES = listOf(
            "transaction id", "txn id", "order id", "reference", "utr", "rrn",
            "debited from", "credited to", "available balance"
        )

        val STATUS_VOCABULARY = setOf(
            "paid", "pay", "payment", "sent", "send", "received", "receive", "cleared",
            "clear", "settled", "settle", "done", "complete", "completed", "success",
            "successful", "successfully", "delivered", "placed", "confirmed", "processing",
            "pending", "failed", "refunded", "reversed", "credited", "debited", "money",
            "amount", "total", "bill", "order", "transaction", "txn", "transfer", "cash"
        )

        /** Grammatical glue, so "Payment has been received" still reads as one status line. */
        val FILLER_WORDS = setOf(
            "a", "an", "the", "is", "was", "has", "have", "been", "to", "from", "of", "on",
            "in", "for", "your", "you", "it", "and", "successfully"
        )

        val UI_CHROME = setOf(
            "help", "back", "close", "menu", "home", "share", "shared", "download",
            "receipt", "new", "date", "time"
        )
    }
}
