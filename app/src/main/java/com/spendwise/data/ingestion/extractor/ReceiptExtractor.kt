package com.spendwise.data.ingestion.extractor

import com.spendwise.data.ingestion.geometry.RowGrouper
import com.spendwise.data.ingestion.geometry.TextRow
import com.spendwise.data.ingestion.model.FieldConfidence
import com.spendwise.data.ingestion.model.RawTransaction
import com.spendwise.data.ingestion.model.TransactionType
import com.spendwise.data.ingestion.normalizer.AmountNormalizer
import com.spendwise.data.ingestion.normalizer.DateNormalizer
import com.spendwise.data.ingestion.ocr.OcrResult
import com.spendwise.data.ingestion.scoring.CurrencyScorer
import com.spendwise.data.ingestion.scoring.DateScorer
import com.spendwise.data.ingestion.scoring.MerchantScorer
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.ExpenseSource
import javax.inject.Inject

/**
 * Extracts a single transaction from a receipt or invoice.
 *
 * The amount is found by locating the row whose label is the strongest "this is what you paid"
 * signal and then taking the rightmost figure on that row. Working row-wise is what separates
 * Total from Sub Total, Cash and Change — labels and values are separate OCR tokens that only
 * relate to each other through their shared row.
 */
class ReceiptExtractor @Inject constructor() {

    fun extract(ocrResult: OcrResult): List<RawTransaction> {
        val words = ocrResult.wordsOrApproximate()
        if (words.isEmpty()) return emptyList()

        val rows = RowGrouper.group(words).filter { it.text.isNotBlank() }
        if (rows.isEmpty()) return emptyList()

        val lineTexts = rows.map { it.text }

        val total = findTotal(rows) ?: return emptyList()

        val merchantCandidates = MerchantScorer.scoreCandidates(ocrResult.linesOrFromText())
        val merchant = merchantCandidates.maxByOrNull { it.score }?.value
            ?.takeIf { it.isNotBlank() }
            ?: fallbackMerchant(lineTexts)
        val merchantConfidence = MerchantScorer.confidenceFrom(merchantCandidates)

        val order = DateNormalizer.resolveOrder(lineTexts)
        val dateCandidates = DateScorer.scoreCandidates(lineTexts, order)
        val dateMillis = dateCandidates.maxByOrNull { it.score }?.value ?: System.currentTimeMillis()
        val dateConfidence = if (dateCandidates.isEmpty()) 0.3f else DateScorer.confidenceFrom(dateCandidates)

        val currencyCandidates = CurrencyScorer.scoreCandidates(ocrResult.fullText)
        val currency = currencyCandidates.maxByOrNull { it.score }?.value ?: Currency.INR
        val currencyConfidence = CurrencyScorer.confidenceFrom(currencyCandidates)

        val category = inferCategory(lineTexts, merchant)
        val fieldConfidence = FieldConfidence(
            title = merchantConfidence,
            amount = total.confidence,
            date = dateConfidence,
            merchant = merchantConfidence,
            currency = currencyConfidence,
            category = if (category != ExpenseCategory.Other) 0.8f else 0.55f
        )

        return listOf(
            RawTransaction(
                title = merchant,
                amount = total.value,
                date = dateMillis,
                merchant = merchant,
                currency = currency,
                category = category,
                type = TransactionType.DEBIT,
                source = ExpenseSource.OCR,
                confidence = (merchantConfidence + dateConfidence + total.confidence + currencyConfidence) / 4f,
                fieldConfidence = fieldConfidence
            )
        )
    }

    private data class Total(val value: Double, val confidence: Float)

    private fun findTotal(rows: List<TextRow>): Total? {
        val scored = rows.mapNotNull { row ->
            val rank = labelRank(row.text) ?: return@mapNotNull null
            val amount = rightmostAmount(row) ?: return@mapNotNull null
            Triple(row, rank, amount)
        }

        // Prefer the strongest label; on a tie take the lowest row, since receipts print the
        // payable figure last.
        val best = scored
            .filter { it.second > 0 }
            .maxWithOrNull(compareBy({ it.second }, { it.first.top }))

        if (best != null) {
            val confidence = when (best.second) {
                LABEL_STRONG -> 0.96f
                LABEL_TOTAL -> 0.9f
                else -> 0.75f
            }
            return Total(best.third, confidence)
        }

        // No usable label. Fall back to the largest decimal figure on a row that isn't an
        // excluded line item, and mark it low so it lands in review.
        val fallback = rows
            .filterNot { EXCLUDED_LABEL.containsMatchIn(it.text.lowercase()) }
            .mapNotNull { rightmostAmount(it, requireDecimal = true) }
            .maxOrNull()
            ?: return null
        return Total(fallback, 0.55f)
    }

    /**
     * Rightmost figure on the row. Receipts right-align values against a left-aligned label,
     * so on "Total 2 items 270.00" the payable amount is the last number, not the largest.
     */
    private fun rightmostAmount(row: TextRow, requireDecimal: Boolean = false): Double? {
        val candidates = row.words.mapNotNull { word ->
            val cleaned = word.text.trim().trim(',', ';', '|', ':')
            if (cleaned.isEmpty()) return@mapNotNull null
            if (DateNormalizer.findFirstToken(cleaned) != null) return@mapNotNull null
            val match = AmountNormalizer.extractAmountMatches(cleaned, allowInteger = !requireDecimal)
                .singleOrNull() ?: return@mapNotNull null
            if (requireDecimal && !match.hasDecimal) return@mapNotNull null
            val digits = match.rawText.count { it.isDigit() }
            if (!match.hasDecimal && digits >= 6) return@mapNotNull null
            word to match.value
        }
        return candidates.maxByOrNull { it.first.right }?.second
    }

    /** Higher is a stronger "amount payable" signal; null means the row is not a total line. */
    private fun labelRank(text: String): Int? {
        val lower = text.lowercase()
        if (EXCLUDED_LABEL.containsMatchIn(lower)) return null
        return when {
            STRONG_LABEL.containsMatchIn(lower) -> LABEL_STRONG
            TOTAL_LABEL.containsMatchIn(lower) -> LABEL_TOTAL
            else -> 0
        }
    }

    private fun inferCategory(lines: List<String>, merchant: String): ExpenseCategory {
        val text = (lines.joinToString(" ") + " " + merchant).lowercase()
        // Word boundaries matter: the previous substring match put every restaurant bill
        // printing "Table 4" into Health because it contained "tab".
        return CATEGORY_RULES.firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }?.second
            ?: ExpenseCategory.Other
    }

    private fun fallbackMerchant(lines: List<String>): String {
        return lines.firstOrNull { line -> BUSINESS_KEYWORD.containsMatchIn(line.lowercase()) }
            ?: lines.firstOrNull { line ->
                line.count { it.isLetter() } >= 4 &&
                    line.count { it.isUpperCase() } > line.length * 0.35
            }
            ?: "Unknown Merchant"
    }

    private companion object {
        const val LABEL_TOTAL = 1
        const val LABEL_STRONG = 2

        val STRONG_LABEL = Regex(
            """\b(grand total|net total|total amount|amount payable|net payable|amount paid|total paid|net amount|you paid|bill amount|invoice total)\b"""
        )
        val TOTAL_LABEL = Regex("""\b(total|payable|paid)\b""")

        /**
         * Rows that mention a total but are never the payable figure. "qty" and "items" are
         * here because "Total Qty: 100" and "Total Items: 2" otherwise outrank the real total.
         */
        val EXCLUDED_LABEL = Regex(
            """\b(sub ?total|taxable|tax|c?gst|sgst|igst|vat|cess|discount|savings|mrp|qty|quantity|items?|cash|change|tender|tendered|round ?off|rounding|balance|advance|due|cashback|reward|points|hsn|invoice no|bill no|gstin)\b"""
        )

        val BUSINESS_KEYWORD = Regex(
            """\b(bazaar|bazar|drug|pharmacy|medical|chemist|store|mart|supermarket|restaurant|hotel|cafe|counter|traders|enterprises|services)\b"""
        )

        val CATEGORY_RULES: List<Pair<Regex, ExpenseCategory>> = listOf(
            Regex("""\b(drug|drugs|pharmacy|pharmacist|chemist|hospital|clinic|doctor|medical|medicine|tablets?|capsules?|dosage)\b""") to ExpenseCategory.Health,
            Regex("""\b(restaurant|cafe|caf|food|dining|diner|meal|kitchen|pizza|burger|bakery|sweets)\b""") to ExpenseCategory.Food,
            Regex("""\b(fuel|petrol|diesel|uber|ola|rapido|taxi|cab|travel|airlines|irctc|railway|toll|parking)\b""") to ExpenseCategory.Travel,
            Regex("""\b(electricity|water bill|broadband|mobile bill|recharge|postpaid|prepaid|gas bill|dth)\b""") to ExpenseCategory.Bills,
            Regex("""\b(mall|mart|store|supermarket|shopping|retail|apparel|fashion|footwear)\b""") to ExpenseCategory.Shopping
        )
    }
}
