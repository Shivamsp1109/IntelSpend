package com.spendwise.data.repository

import android.util.Log
import com.spendwise.data.local.IncomeDao
import com.spendwise.data.local.toDomain
import com.spendwise.data.local.toEntity
import com.spendwise.data.remote.MySqlIncomeDataSource
import com.spendwise.domain.model.Income
import com.spendwise.domain.repository.IncomeRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class IncomeRepositoryImpl @Inject constructor(
    private val dao: IncomeDao,
    private val remoteDataSource: MySqlIncomeDataSource
) : IncomeRepository {

    override fun observeIncomes(): Flow<List<Income>> =
        dao.observeIncomes().map { list -> list.map { it.toDomain() } }

    override fun searchIncomes(query: String, source: String?): Flow<List<Income>> =
        dao.searchIncomes(query, source).map { list -> list.map { it.toDomain() } }

    override fun observePendingSyncCount(): Flow<Int> =
        dao.observePendingSyncCount()

    override suspend fun addIncome(income: Income) {
        val entity = income.copy(isSynced = false).toEntity()
        val inserted = entity.copy(id = dao.insertIncome(entity).toInt())
        syncIncomeOrEnqueueRetry(inserted)
    }

    override suspend fun updateIncome(income: Income) {
        val entity = income.copy(isSynced = false).toEntity()
        dao.updateIncome(entity)
        syncIncomeOrEnqueueRetry(entity)
    }

    override suspend fun deleteIncome(income: Income) {
        dao.deleteIncome(income.toEntity())
    }

    override suspend fun syncPendingIncomes() {
        dao.getPendingSync().forEach { entity ->
            runCatching {
                remoteDataSource.upsertIncome(entity)
                dao.updateIncome(entity.copy(isSynced = true))
            }.onFailure { error ->
                Log.w(TAG, "Failed to sync income id=${entity.id}; will retry.", error)
            }
        }
    }

    private suspend fun syncIncomeOrEnqueueRetry(
        entity: com.spendwise.data.local.IncomeEntity
    ) {
        runCatching {
            remoteDataSource.upsertIncome(entity)
            dao.updateIncome(entity.copy(isSynced = true))
        }.onFailure { error ->
            Log.w(TAG, "Immediate income sync failed; will be retried by SyncWorker.", error)
        }
    }

    private companion object {
        const val TAG = "IncomeRepository"
    }
}

