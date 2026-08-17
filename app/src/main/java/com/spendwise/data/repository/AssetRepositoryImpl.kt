package com.spendwise.data.repository

import android.util.Log
import com.spendwise.data.local.AssetDao
import com.spendwise.data.local.AssetEntity
import com.spendwise.data.local.toDomain
import com.spendwise.data.local.toEntity
import com.spendwise.data.remote.MySqlAssetDataSource
import com.spendwise.domain.model.Asset
import com.spendwise.domain.repository.AssetRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Local-first, like every other repository here: the write lands in Room
 * immediately and the upload is attempted afterwards, so recording a holding
 * works with no signal and reaches the server on the next sweep.
 */
class AssetRepositoryImpl @Inject constructor(
    private val dao: AssetDao,
    private val remoteDataSource: MySqlAssetDataSource
) : AssetRepository {

    override fun observeAssets(): Flow<List<Asset>> =
        dao.observeAssets().map { list -> list.map { it.toDomain() } }

    override suspend fun getById(id: Int): Asset? = dao.getById(id)?.toDomain()

    override suspend fun addAsset(asset: Asset) {
        val entity = asset.toEntity().copy(isSynced = false)
        val inserted = entity.copy(id = dao.insertAsset(entity).toInt())
        syncOrLog(inserted)
    }

    override suspend fun updateAsset(asset: Asset) {
        val entity = asset.toEntity().copy(isSynced = false)
        dao.updateAsset(entity)
        syncOrLog(entity)
    }

    override suspend fun deleteAsset(asset: Asset) {
        dao.deleteAsset(asset.toEntity())
        // Removed from the server directly rather than through the sweep: a
        // deletion is an absence, and the sweep only ever finds rows present.
        runCatching { remoteDataSource.deleteAsset(asset.id) }
            .onFailure { Log.w(TAG, "Could not remove holding ${asset.id} remotely.", it) }
    }

    /** See ExpenseRepositoryImpl.syncPendingExpenses — same shape, same reason. */
    override suspend fun syncPendingAssets() {
        val uploaded = mutableListOf<Int>()
        for (entity in dao.getPendingSync()) {
            runCatching { remoteDataSource.upsertAsset(entity) }
                .onSuccess { uploaded += entity.id }
                .onFailure { error ->
                    Log.w(TAG, "Failed to sync holding id=${entity.id}; will retry.", error)
                }

            if (uploaded.size >= MARK_SYNCED_BATCH) {
                dao.markSynced(uploaded.toList())
                uploaded.clear()
            }
        }
        if (uploaded.isNotEmpty()) dao.markSynced(uploaded)
    }

    private suspend fun syncOrLog(entity: AssetEntity) {
        runCatching {
            remoteDataSource.upsertAsset(entity)
            dao.updateAsset(entity.copy(isSynced = true))
        }.onFailure { error ->
            Log.w(TAG, "Immediate holding sync failed; SyncWorker will retry.", error)
        }
    }

    private companion object {
        const val TAG = "AssetRepository"

        /** See ExpenseRepositoryImpl.MARK_SYNCED_BATCH. */
        const val MARK_SYNCED_BATCH = 50
    }
}
