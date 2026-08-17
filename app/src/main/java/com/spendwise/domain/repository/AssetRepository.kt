package com.spendwise.domain.repository

import com.spendwise.domain.model.Asset
import kotlinx.coroutines.flow.Flow

interface AssetRepository {
    fun observeAssets(): Flow<List<Asset>>
    suspend fun getById(id: Int): Asset?
    suspend fun addAsset(asset: Asset)
    suspend fun updateAsset(asset: Asset)
    suspend fun deleteAsset(asset: Asset)
    suspend fun syncPendingAssets()
}
