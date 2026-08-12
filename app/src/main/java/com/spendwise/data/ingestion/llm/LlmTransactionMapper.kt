package com.spendwise.data.ingestion.llm

import com.spendwise.data.ingestion.model.FieldConfidence
import com.spendwise.data.ingestion.model.RawTransaction
import com.spendwise.data.ingestion.model.TransactionType
import com.spendwise.data.ingestion.normalizer.AmountNormalizer
import com.spendwise.data.ingestion.normalizer.CurrencyNormalizer
import com.spendwise.data.ingestion.normalizer.DateNormalizer
import com.spendwise.data.remote.ExtractedTransaction
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.ExpenseSource
import javax.inject.Inject
import kotlin.math.abs

/**
 * Converts model output into [RawTransaction], verifying it against the text OCR
 * actually read from the image.
 *
 * A vision model can transpose a digit or invent a plausible-looking figure, and
 * unlike a parsing bug that failure is silent and confident. The deterministic
 * pipeline already extracts every number on the page, so it costs nothing to
 * check the model's answer against that list. Anything unverified still reaches
 * the user — flagged for review rather than dropped or imported quietly.
 */
class LlmTransactionMapper @Inject constructor() {

    fun map(
        extracted: List<ExtractedTransaction>,
        ocrText: String,
        source: ExpenseSource
    ): List<RawTransaction> {
        val numbersOnPage = AmountNormalizer
            .extractAmountMatches(ocrText, allowInteger = true)
            .map { it.value }

        return extracted.mapNotNull { item -> toTransaction(item, numbersOnPage, ocrText, source) }
    }

    private fun toTransaction(
        item: ExtractedTransaction,
        numbersOnPage: List<Double>,
        ocrText: String,
        source: ExpenseSource
    ): RawTransaction? {
        val amount = abs(item.amount)
        if (amount <= 0.0) return null

        // Verified when the figure genuinely appears in the page text. Tolerance
        // covers OCR reading 1,299.00 where the model reports 1299.
        val amountVerified = numbersOnPage.any { abs(it - amount) <= AMOUNT_TOLERANCE }

        // The model also quotes the text it read the amount from; if that quote
        // isn't on the page either, it's a strong signal the value was invented.
        val quoteVerified = item.amountSource.isNotBlank() &&
            ocrText.contains(item.amountSource.trim(), ignoreCase = true)

        val dateMillis = item.date?.let { DateNormalizer.normalize(it) }
        val merchant = item.merchant.trim().takeIf { it.isNotEmpty() }

        val modelConfidence = item.confidence.coerceIn(0.0, 1.0).toFloat()
        val amountConfidence = when {
            amountVerified && quoteVerified -> minOf(modelConfidence, 0.97f)
            amountVerified -> minOf(modelConfidence, 0.9f)
            // Unverified: cap below the review threshold so it can never be
            // imported without someone looking at it.
            else -> minOf(modelConfidence, 0.55f)
        }

        val dateConfidence = when {
            dateMillis == null -> 0.3f
            else -> minOf(modelConfidence, 0.9f)
        }
        val merchantConfidence = if (merchant != null) minOf(modelConfidence, 0.9f) else 0.4f

        return RawTransaction(
            title = merchant?.take(40) ?: "Imported transaction",
            amount = amount,
            date = dateMillis ?: System.currentTimeMillis(),
            merchant = merchant,
            currency = CurrencyNormalizer.normalize(item.currency),
            category = ExpenseCategory.fromLabel(item.category),
            type = if (item.direction.equals("CREDIT", ignoreCase = true)) {
                TransactionType.CREDIT
            } else {
                TransactionType.DEBIT
            },
            source = source,
            confidence = amountConfidence,
            fieldConfidence = FieldConfidence(
                title = merchantConfidence,
                amount = amountConfidence,
                date = dateConfidence,
                merchant = merchantConfidence,
                currency = 0.9f,
                category = minOf(modelConfidence, 0.85f)
            )
        )
    }

    private companion object {
        const val AMOUNT_TOLERANCE = 0.02
    }
}
