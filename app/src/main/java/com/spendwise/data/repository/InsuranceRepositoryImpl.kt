package com.spendwise.data.repository

import android.util.Log
import com.spendwise.data.local.InsurancePolicyDao
import com.spendwise.data.local.InsurancePolicyEntity
import com.spendwise.data.local.toDomain
import com.spendwise.data.local.toEntity
import com.spendwise.data.remote.MySqlInsuranceDataSource
import com.spendwise.domain.model.InsurancePolicy
import com.spendwise.domain.repository.InsuranceRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class InsuranceRepositoryImpl @Inject constructor(
    private val dao: InsurancePolicyDao,
    private val remoteDataSource: MySqlInsuranceDataSource
) : InsuranceRepository {

    override fun observePolicies(): Flow<List<InsurancePolicy>> =
        dao.observePolicies().map { list -> list.map { it.toDomain() } }

    override suspend fun getById(id: Int): InsurancePolicy? = dao.getById(id)?.toDomain()

    override suspend fun addPolicy(policy: InsurancePolicy) {
        val entity = policy.toEntity().copy(isSynced = false)
        val inserted = entity.copy(id = dao.insertPolicy(entity).toInt())
        syncOrLog(inserted)
    }

    override suspend fun updatePolicy(policy: InsurancePolicy) {
        val entity = policy.toEntity().copy(isSynced = false)
        dao.updatePolicy(entity)
        syncOrLog(entity)
    }

    override suspend fun deletePolicy(policy: InsurancePolicy) {
        dao.deletePolicy(policy.toEntity())
        runCatching { remoteDataSource.deletePolicy(policy.id) }
            .onFailure { Log.w(TAG, "Could not remove policy ${policy.id} remotely.", it) }
    }

    /** See ExpenseRepositoryImpl.syncPendingExpenses — same shape, same reason. */
    override suspend fun syncPendingPolicies() {
        val uploaded = mutableListOf<Int>()
        for (entity in dao.getPendingSync()) {
            runCatching { remoteDataSource.upsertPolicy(entity) }
                .onSuccess { uploaded += entity.id }
                .onFailure { error ->
                    Log.w(TAG, "Failed to sync policy id=${entity.id}; will retry.", error)
                }

            if (uploaded.size >= MARK_SYNCED_BATCH) {
                dao.markSynced(uploaded.toList())
                uploaded.clear()
            }
        }
        if (uploaded.isNotEmpty()) dao.markSynced(uploaded)
    }

    private suspend fun syncOrLog(entity: InsurancePolicyEntity) {
        runCatching {
            remoteDataSource.upsertPolicy(entity)
            dao.updatePolicy(entity.copy(isSynced = true))
        }.onFailure { error ->
            Log.w(TAG, "Immediate policy sync failed; SyncWorker will retry.", error)
        }
    }

    private companion object {
        const val TAG = "InsuranceRepository"
        const val MARK_SYNCED_BATCH = 50
    }
}
