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

        // 1. Check learned categories
        val learned = learnedCategoryStore.getCategoryForMerchant(normalizedMerchant)
        if (learned != null) {
            return@withContext try {
                ExpenseCategory.valueOf(learned)
            } catch (e: Exception) {
                ExpenseCategory.Other
            }
        }

        // 2. Check bundled fallback
        if (bundledMap == null) {
            loadBundledMap()
        }

        val match = bundledMap?.entries?.find { 
            normalizedMerchant.contains(it.key, ignoreCase = true) 
        }
        return@withContext match?.value ?: ExpenseCategory.Other
    }

    private fun loadBundledMap() {
        try {
            val jsonString = context.assets.open("merchant_category.json").bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(jsonString)
            val map = mutableMapOf<String, ExpenseCategory>()
            for (key in jsonObject.keys()) {
                val catString = jsonObject.getString(key)
                try {
                    val category = ExpenseCategory.valueOf(catString)
                    map[key.lowercase()] = category
                } catch (e: Exception) {
                    // Ignore invalid categories
                }
            }
            bundledMap = map
        } catch (e: Exception) {
            e.printStackTrace()
            bundledMap = emptyMap()
        }
    }
}
