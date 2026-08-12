package com.spendwise.data.ingestion.legacy

import com.spendwise.data.ingestion.model.RawTransaction
import com.spendwise.data.ingestion.model.TransactionType
import com.spendwise.data.ingestion.normalizer.AmountNormalizer
import com.spendwise.data.ingestion.normalizer.CurrencyNormalizer
import com.spendwise.data.ingestion.normalizer.MerchantNormalizer
import com.spendwise.data.ingestion.ocr.OcrLine
import com.spendwise.data.ingestion.ocr.OcrResult
import com.spendwise.data.ingestion.scoring.AmountScorer
import com.spendwise.domain.model.ExpenseSource
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/**
 * The statement pipeline as it was before this change, kept in the test sources purely to
 * measure against. Nothing in production references it.
 *
 * Two details are reproduced deliberately because they were the source of the inaccuracy:
 *
 *  - [wrapTextInOcrResult] fabricates coordinates from character offsets, exactly as the
 *    orchestrator did for every text-layer PDF. Column alignment does not survive that
 *    transform, so the clustering below is operating on noise.
 *  - [LegacyDateNormalizer] tries dd/MM before MM/dd unconditionally and never rejects an
 *    implausible result, so a US-format date parses to the wrong day and reports no error.
 */
class LegacyStatementExtractor {

    private val dateRegex = Regex(
        """\b(?:\d{4}[/-]\d{1,2}[/-]\d{1,2}|\d{1,2}[./-]\d{1,2}[./-]\d{2,4}|\d{1,2}[- ]?[A-Za-z]{3,9}[- ]?\d{2,4}|[A-Za-z]{3,9}\s+\d{1,2},?\s+\d{2,4})\b"""
    )

    /** How the orchestrator turned PDFTextStripper output into "geometry". */
    fun wrapTextInOcrResult(text: String): OcrResult {
        val lines = text.split("\n").mapIndexed { index, lineText ->
            OcrLine(
                text = lineText,
                left = 0,
                top = index * 24,
                right = lineText.length * 8,
                height = 20
            )
        }
        return OcrResult(text, lines)
    }

    fun extract(ocrResult: OcrResult): List<RawTransaction> {
        val clockRegex = Regex("""^\d{1,2}:\d{2}(\s*[AP]M)?$""", RegexOption.IGNORE_CASE)
        val lines = ocrResult.lines.filter { it.text.isNotBlank() && !clockRegex.matches(it.text.trim()) }
        if (lines.isEmpty()) return emptyList()
        val currency = CurrencyNormalizer.normalize(ocrResult.fullText)

        var debitHeaderX: Float? = null
        var creditHeaderX: Float? = null
        var balanceHeaderX: Float? = null

        for (line in lines) {
            val lower = line.text.lowercase()
            val charWidth = (line.right - line.left).toFloat() / line.text.length.coerceAtLeast(1)

            if (lower.contains("debit") || lower.contains("withdrawal") || lower.contains("payment")) {
                val idx = lower.indexOf("debit").coerceAtLeast(lower.indexOf("withdrawal")).coerceAtLeast(lower.indexOf("payment"))
                if (idx >= 0) debitHeaderX = line.left + charWidth * idx
            }
            if (lower.contains("credit") || lower.contains("deposit") || lower.contains("receipt")) {
                val idx = lower.indexOf("credit").coerceAtLeast(lower.indexOf("deposit")).coerceAtLeast(lower.indexOf("receipt"))
                if (idx >= 0) creditHeaderX = line.left + charWidth * idx
            }
            if (lower.contains("balance") || lower.contains("bal")) {
                val idx = lower.indexOf("balance").coerceAtLeast(lower.indexOf("bal"))
                if (idx >= 0) balanceHeaderX = line.left + charWidth * idx
            }
        }

        data class LineCandidate(
            val line: OcrLine,
            val dateMillis: Long,
            val dateMatchValue: String,
            val amount: Double,
            val rawAmount: String,
            val approxLeft: Float,
            val isBalanceLike: Boolean
        )

        val lineCandidates = mutableListOf<LineCandidate>()

        for (line in lines) {
            val dateMatch = dateRegex.find(line.text) ?: continue
            val dateMillis = LegacyDateNormalizer.normalize(dateMatch.value) ?: continue
            val body = line.text.removeRange(dateMatch.range).trim()
            if (body.isBlank()) continue

            val amounts = AmountNormalizer.extractAmountMatches(body, allowInteger = false)
            if (amounts.isEmpty()) continue

            val charWidth = (line.right - line.left).toFloat() / line.text.length.coerceAtLeast(1)

            for (amount in amounts) {
                val idx = line.text.indexOf(amount.rawText).takeIf { it >= 0 }
                    ?: (dateMatch.range.last + 1 + amount.start).coerceAtMost(line.text.length)
                val approxLeft = line.left + charWidth * idx
                val lowerLine = line.text.lowercase()
                lineCandidates.add(
                    LineCandidate(
                        line, dateMillis, dateMatch.value, amount.value, amount.rawText, approxLeft,
                        lowerLine.contains("balance") || lowerLine.contains("bal")
                    )
                )
            }
        }

        val clusters = clusterXCoordinates(lineCandidates.map { it.approxLeft }, tolerance = 35f)

        var detectedBalanceCluster: List<Float>? = null
        var detectedDebitCluster: List<Float>? = null
        var detectedCreditCluster: List<Float>? = null

        if (clusters.isNotEmpty()) {
            val sortedClusters = clusters.sortedBy { it.average() }
            val rightmostCluster = sortedClusters.last()
            val hasBalanceHeaderAlign = balanceHeaderX?.let { bhX -> rightmostCluster.any { abs(it - bhX) < 40f } } ?: false
            val hasBalanceLikeLines = lineCandidates.filter { it.isBalanceLike }.map { it.approxLeft }.any { rightmostCluster.contains(it) }

            if (hasBalanceHeaderAlign || hasBalanceLikeLines || sortedClusters.size >= 2) {
                detectedBalanceCluster = rightmostCluster
            }

            val potentialAmountClusters = sortedClusters.filter { it != detectedBalanceCluster }
            if (potentialAmountClusters.size == 2) {
                detectedDebitCluster = potentialAmountClusters[0]
                detectedCreditCluster = potentialAmountClusters[1]
            } else if (potentialAmountClusters.size == 1) {
                val cluster = potentialAmountClusters.single()
                val alignDebit = debitHeaderX?.let { dhX -> cluster.any { abs(it - dhX) < 40f } } ?: false
                val alignCredit = creditHeaderX?.let { chX -> cluster.any { abs(it - chX) < 40f } } ?: false
                if (alignCredit && !alignDebit) detectedCreditCluster = cluster else detectedDebitCluster = cluster
            }
        }

        val transactions = mutableListOf<RawTransaction>()
        val candidatesByLine = lineCandidates.groupBy { it.line }

        for ((line, candidates) in candidatesByLine) {
            val dateMatch = dateRegex.find(line.text)!!
            val body = line.text.removeRange(dateMatch.range).trim()
            val rawDescription = AmountNormalizer.removeAmountTokens(body, allowInteger = false)
                .replace(Regex("\\b(available balance|closing balance|opening balance|balance)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ")
                .trim(' ', '-', '|', ',')
            if (rawDescription.length < 3) continue
            val merchant = MerchantNormalizer.normalizeDescription(rawDescription)

            val nonBalanceCandidates = candidates.filter { c ->
                detectedBalanceCluster?.let { bc -> !bc.any { abs(c.approxLeft - it) < 15f } } ?: true
            }
            val targetCandidates = nonBalanceCandidates.ifEmpty { candidates }

            val amountCandidates = targetCandidates.map { candidate ->
                val match = AmountNormalizer.AmountMatch(
                    value = candidate.amount,
                    rawText = candidate.rawAmount,
                    start = 0,
                    end = candidate.rawAmount.length,
                    hasCurrencyToken = CurrencyNormalizer.detect(candidate.rawAmount) != null,
                    hasDecimal = Regex("[.,]\\d{1,2}\\b").containsMatchIn(candidate.rawAmount)
                )
                AmountScorer.scoreCandidate(candidate.line.text, match)
            }
            val bestAmountCandidate = amountCandidates.maxByOrNull { it.score }
            val amount = bestAmountCandidate?.value ?: targetCandidates.first().amount

            val bestCandidate = targetCandidates.find { it.amount == amount } ?: targetCandidates.first()
            val isCreditCluster = detectedCreditCluster?.any { abs(bestCandidate.approxLeft - it) < 15f } ?: false
            val isDebitCluster = detectedDebitCluster?.any { abs(bestCandidate.approxLeft - it) < 15f } ?: false

            val upper = line.text.uppercase()
            val hasCreditText = Regex("\\b(CR|CREDIT|DEPOSIT|REFUND|RECEIVED)\\b").containsMatchIn(upper)

            val isCredit = when {
                isCreditCluster -> true
                isDebitCluster -> false
                else -> hasCreditText
            }

            transactions.add(
                RawTransaction(
                    title = merchant.take(40),
                    amount = amount,
                    date = bestCandidate.dateMillis,
                    merchant = merchant,
                    currency = currency,
                    type = if (isCredit) TransactionType.CREDIT else TransactionType.DEBIT,
                    source = ExpenseSource.PDF
                )
            )
        }

        return transactions
    }

    private fun clusterXCoordinates(coords: List<Float>, tolerance: Float): List<List<Float>> {
        val sorted = coords.sorted()
        val clusters = mutableListOf<MutableList<Float>>()
        for (x in sorted) {
            val cluster = clusters.find { c -> c.any { abs(it - x) <= tolerance } }
            if (cluster != null) cluster.add(x) else clusters.add(mutableListOf(x))
        }
        return clusters
    }
}

/** The previous date parser: fixed format precedence, no plausibility check. */
object LegacyDateNormalizer {
    fun normalize(raw: String): Long? {
        val clean = extractDateCandidate(raw) ?: return null
        val formats = listOf(
            "dd/MM/yyyy", "dd/MM/yy", "dd-MM-yyyy", "dd-MM-yy", "dd.MM.yyyy", "dd.MM.yy",
            "yyyy-MM-dd", "yyyy/MM/dd", "MM/dd/yyyy", "MM/dd/yy", "M/d/yyyy", "M/d/yy",
            "dd MMM yyyy", "dd MMM yy", "d MMM yyyy", "d MMM yy", "dd-MMM-yyyy", "dd-MMM-yy",
            "d-MMM-yyyy", "d-MMM-yy", "MMM dd yyyy", "MMM d yyyy", "MMM dd, yyyy", "MMM d, yyyy"
        )
        for (format in formats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.US)
                sdf.isLenient = false
                val date = sdf.parse(clean)
                if (date != null) return date.time
            } catch (e: Exception) {
                // Ignore and try next format
            }
        }
        return null
    }

    private fun extractDateCandidate(raw: String): String? {
        val text = raw.trim()
        Regex("\\b\\d{4}[/-]\\d{1,2}[/-]\\d{1,2}\\b").find(text)?.value?.let { return it }
        Regex("\\b\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}\\b").find(text)?.value?.let { return it }
        Regex("\\b\\d{1,2}\\.\\d{1,2}\\.\\d{2,4}\\b").find(text)?.value?.let { return it }
        Regex("\\b\\d{1,2}[- ]?[A-Za-z]{3,9}[- ]?\\d{2,4}\\b").find(text)?.value?.let { return it }
        return Regex("\\b[A-Za-z]{3,9}\\s+\\d{1,2},?\\s+\\d{2,4}\\b").find(text)?.value
    }
}
