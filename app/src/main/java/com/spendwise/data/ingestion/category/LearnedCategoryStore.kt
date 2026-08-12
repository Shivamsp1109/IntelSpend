package com.spendwise.data.ingestion.category

import com.spendwise.data.local.LearnedCategoryDao
import com.spendwise.data.local.LearnedCategoryEntity
import com.spendwise.data.ingestion.normalizer.MerchantNormalizer
import javax.inject.Inject

class LearnedCategoryStore @Inject constructor(
    private val dao: LearnedCategoryDao
) {
    suspend fun getCategoryForMerchant(merchant: String): String? {
        val normalized = MerchantNormalizer.normalize(merchant)
        return dao.getCategoryForMerchant(normalized)
    }

    suspend fun learnCategory(merchant: String, category: String) {
        val normalized = MerchantNormalizer.normalize(merchant)
        dao.insertLearnedCategory(
            LearnedCategoryEntity(
                merchant = normalized,
                category = category,
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}
