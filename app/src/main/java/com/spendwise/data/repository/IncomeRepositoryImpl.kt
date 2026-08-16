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
    private val remoteDataSource: MySqlIncomeDataSource,
    private val syncScheduler: com.spendwise.util.SyncScheduler
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

    override suspend fun addIncomesBatch(incomes: List<Income>) {
        val entities = incomes.map { it.copy(isSynced = false).toEntity() }
        dao.insertIncomes(entities)
        syncScheduler.enqueueImmediateSync()
    }

    override suspend fun updateIncome(income: Income) {
        val entity = income.copy(isSynced = false).toEntity()
        dao.updateIncome(entity)
        syncIncomeOrEnqueueRetry(entity)
    }

    override suspend fun deleteIncome(income: Income) {
        val entity = income.toEntity()
        dao.deleteIncome(entity)
        if (entity.id > 0) {
            val deleteSync = com.spendwise.data.local.IncomeDeleteSyncEntity(localId = entity.id)
            dao.insertPendingDelete(deleteSync)
            syncDeleteOrLeave(entity.id)
        }
    }

    /**
     * See ExpenseRepositoryImpl.syncPendingExpenses — same shape, same reason.
     * Marking each row synced individually invalidated every observer of the
     * incomes table once per row, which during a large batch made the app
     * visibly stall for as long as the sync ran.
     */
    override suspend fun syncPendingIncomes() {
        // 1. Upserts
        val uploaded = mutableListOf<Int>()
        for (entity in dao.getPendingSync()) {
            runCatching { remoteDataSource.upsertIncome(entity) }
                .onSuccess { uploaded += entity.id }
                .onFailure { error ->
                    Log.w(TAG, "Failed to sync income id=${entity.id}; will retry.", error)
                }

            if (uploaded.size >= MARK_SYNCED_BATCH) {
                dao.markSynced(uploaded.toList())
                uploaded.clear()
            }
        }
        if (uploaded.isNotEmpty()) dao.markSynced(uploaded)

        // 2. Deletions
        dao.getPendingDeleteSync().forEach { delete ->
            runCatching {
                remoteDataSource.deleteIncome(delete.localId)
                dao.deletePendingDelete(delete.localId)
            }.onFailure { error ->
                Log.w(TAG, "Failed to sync delete income localId=${delete.localId}; will retry.", error)
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
            Log.w(TAG, "Immediate income sync failed; scheduling retry.", error)
            syncScheduler.enqueueImmediateSync()
        }
    }

    private suspend fun syncDeleteOrLeave(localId: Int) {
        runCatching {
            remoteDataSource.deleteIncome(localId)
            dao.deletePendingDelete(localId)
        }.onFailure { error ->
            Log.w(TAG, "Immediate income deletion sync failed for id=$localId; scheduling retry.", error)
            syncScheduler.enqueueImmediateSync()
        }
    }

    private companion object {
        const val TAG = "IncomeRepository"

        /** See ExpenseRepositoryImpl.MARK_SYNCED_BATCH. */
        const val MARK_SYNCED_BATCH = 50
    }
}

