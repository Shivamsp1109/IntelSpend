package com.spendwise.data.ingestion.category

import android.content.Context
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.data.ingestion.normalizer.MerchantNormalizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryPredictor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val learnedCategoryStore: LearnedCategoryStore
) {
    private var bundledMap: Map<String, ExpenseCategory>? = null

    suspend fun predict(merchant: String): ExpenseCategory = withContext(Dispatchers.IO) {
        if (merchant.isBlank()) return@withContext ExpenseCategory.Other

        val normalizedMerchant = MerchantNormalizer.normalize(merchant)

        // 1. What the user has already corrected. Their answer always wins.
        //
        // Resolved through fromLabel rather than valueOf so entries saved under
        // the old seven-category names still work: "Food" learned last year
        // resolves to Food & Dining rather than throwing and landing in Other.
        val learned = learnedCategoryStore.getCategoryForMerchant(normalizedMerchant)
        if (learned != null) {
            return@withContext ExpenseCategory.fromLabel(learned)
        }

        // 2. The bundled map.
        if (bundledMap == null) {
            loadBundledMap()
        }

        // Longest key wins, because keys overlap and the more specific one is
        // the right answer: "Swiggy Instamart" contains both "swiggy" and
        // "swiggy instamart", and it is groceries, not a restaurant meal.
        // Taking the first match instead left that to map iteration order.
        val match = bundledMap
            ?.filterKeys { normalizedMerchant.contains(it, ignoreCase = true) }
            ?.maxByOrNull { it.key.length }

        return@withContext match?.value ?: ExpenseCategory.Other
    }

    private fun loadBundledMap() {
        try {
            val jsonString = context.assets.open("merchant_category.json").bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)
            val map = mutableMapOf<String, ExpenseCategory>()
            for (key in jsonObject.keys()) {
                // Keys beginning with an underscore are notes to whoever edits
                // the file, not merchants.
                if (key.startsWith("_")) continue

                val category = ExpenseCategory.fromLabel(jsonObject.getString(key))
                // A typo in the asset would otherwise silently register as Other
                // and look like a working entry.
                if (category != ExpenseCategory.Other) {
                    map[key.lowercase()] = category
                }
            }
            bundledMap = map
        } catch (e: Exception) {
            e.printStackTrace()
            bundledMap = emptyMap()
        }
    }
}
