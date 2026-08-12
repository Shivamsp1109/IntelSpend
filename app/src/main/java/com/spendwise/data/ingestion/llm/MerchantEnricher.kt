package com.spendwise.data.ingestion.llm

import com.spendwise.data.ingestion.model.RawTransaction
import com.spendwise.data.remote.ExtractionDataSource
import com.spendwise.domain.model.ExpenseCategory
import javax.inject.Inject

/**
 * Polishes payee names that came out of a deterministic parse.
 *
 * Banks truncate the payee to a fixed width before writing the PDF — "Amazon
 * India" arrives as "Amazon I". Positional parsing finds that fragment reliably,
 * but no rule can restore characters the bank discarded, so a model fills in the
 * brand behind the fragment.
 *
 * Deliberately narrow: amounts, dates and direction stay deterministic and
 * balance-verified. Only the name and, where it is still unset, the category are
 * modelled — so a bad enrichment can mislabel a row but can never corrupt the
 * figures.
 */
class MerchantEnricher @Inject constructor(
    private val extractionDataSource: ExtractionDataSource
) {
    suspend fun enrich(transactions: List<RawTransaction>): List<RawTransaction> {
        // Deduplicated: a statement repeats the same payees many times, and the
        // request is billed per token.
        val names = transactions
            .mapNotNull { it.merchant?.trim()?.takeIf { name -> name.isNotEmpty() } }
            .distinct()
        if (names.isEmpty()) return transactions

        val enriched = extractionDataSource.enrichMerchants(names)
        if (enriched.isEmpty()) return transactions

        return transactions.map { transaction ->
            val original = transaction.merchant?.trim().orEmpty()
            val match = enriched[original] ?: return@map transaction
            val cleaned = match.merchant.trim().takeIf { it.isNotEmpty() } ?: return@map transaction

            // The model returns the fragment unchanged when it doesn't recognise
            // it, so a low-confidence answer is already a no-op on the name. The
            // category is a genuine guess though, so only take it when the model
            // is confident and the local parse had nothing better.
            val category = if (
                match.confidence >= CATEGORY_CONFIDENCE &&
                transaction.category == ExpenseCategory.Other
            ) {
                ExpenseCategory.fromLabel(match.category)
            } else {
                transaction.category
            }

            transaction.copy(
                title = cleaned.take(40),
                merchant = cleaned,
                category = category,
                fieldConfidence = transaction.fieldConfidence.copy(
                    title = match.confidence.toFloat().coerceIn(0f, 1f),
                    merchant = match.confidence.toFloat().coerceIn(0f, 1f)
                )
            )
        }
    }

    private companion object {
        const val CATEGORY_CONFIDENCE = 0.6
    }
}
