package com.spendwise.data.ingestion.category

import android.util.Log
import com.spendwise.data.ingestion.model.RawTransaction
import com.spendwise.data.remote.CategoriseItem
import com.spendwise.data.remote.ExtractionDataSource
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.TransactionNature
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Works out what each imported transaction was for.
 *
 * A cascade, cheapest first:
 *
 *  1. **What the user has already corrected.** Their own answer always wins.
 *  2. **The bundled merchant map.** Offline, instant, free.
 *  3. **The model**, for whatever is left — deduplicated by merchant and sent as
 *     one request.
 *
 * The deduplication is the whole economy of it. A month of daily coffees is one
 * entry in one request rather than thirty calls, and the answer is written back
 * to the learned store, so that merchant is paid for once and never again. A
 * thousand transactions across a few hundred distinct merchants costs about a
 * rupee; the second import of the same shops costs nothing.
 */
@Singleton
class CategoryResolver @Inject constructor(
    private val categoryPredictor: CategoryPredictor,
    private val learnedCategoryStore: LearnedCategoryStore,
    private val extractionDataSource: ExtractionDataSource
) {

    suspend fun resolve(
        transactions: List<RawTransaction>,
        useModelForUnknown: Boolean
    ): List<RawTransaction> {
        if (transactions.isEmpty()) return transactions

        // Steps 1 and 2, on device.
        val locallyResolved = transactions.map { tx ->
            val predicted = tx.merchant?.let { categoryPredictor.predict(it) } ?: ExpenseCategory.Other
            // A category the document stated outright beats a guess from the
            // merchant name, so a prediction of Other never overwrites it.
            if (predicted == ExpenseCategory.Other) tx else tx.copy(category = predicted)
        }

        if (!useModelForUnknown) return locallyResolved

        val unresolved = locallyResolved
            .filter { it.category == ExpenseCategory.Other && !it.merchant.isNullOrBlank() }
            .distinctBy { it.merchant!!.lowercase() }
            .take(MAX_BATCH)
            .map { tx ->
                CategoriseItem(
                    merchant = tx.merchant!!,
                    // The narration disambiguates what the name cannot: IRCTC is
                    // Travel for a ticket and Transport for a platform fee.
                    narration = tx.notes ?: tx.title.takeIf { it != tx.merchant },
                    amount = tx.amount
                )
            }

        if (unresolved.isEmpty()) return locallyResolved

        val answers = extractionDataSource.categorise(unresolved)
        if (answers.isEmpty()) return locallyResolved

        // Remembered before being applied, so the next import of the same shop
        // resolves at step 1 without another request.
        answers.forEach { (merchant, result) ->
            if (result.confidence >= LEARN_ABOVE) {
                runCatching {
                    learnedCategoryStore.learnCategory(
                        merchant,
                        ExpenseCategory.fromLabel(result.category).name
                    )
                }.onFailure { Log.w(TAG, "Could not remember a category for $merchant.", it) }
            }
        }

        return locallyResolved.map { tx ->
            val answer = tx.merchant?.let { name ->
                answers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            } ?: return@map tx

            val category = ExpenseCategory.fromLabel(answer.category)
            val nature = TransactionNature.fromName(answer.nature)

            tx.copy(
                // A low-confidence guess is left as Other rather than applied.
                // The user can see and fix Other; a plausible wrong category
                // hides, and hidden wrong data is worse than visibly missing data.
                category = if (answer.confidence >= APPLY_ABOVE) category else tx.category,
                // Nature is applied regardless of confidence when it is not
                // ordinary spending: wrongly counting a transfer as an expense
                // distorts the whole month, while wrongly excluding one costs a
                // single row the user can put back.
                nature = if (nature != TransactionNature.Spending) nature else tx.nature
            )
        }
    }

    private companion object {
        const val TAG = "CategoryResolver"

        /** Matches the server's per-request ceiling. */
        const val MAX_BATCH = 100

        /** Below this the answer is shown as Other rather than applied. */
        const val APPLY_ABOVE = 0.6

        /** Only confident answers are remembered; a guess should not become a fact. */
        const val LEARN_ABOVE = 0.75
    }
}
