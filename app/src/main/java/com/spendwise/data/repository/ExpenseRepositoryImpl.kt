package com.spendwise.data.repository

import android.util.Log
import com.spendwise.data.local.ExpenseDao
import com.spendwise.data.local.ExpenseDeleteSyncEntity
import com.spendwise.data.local.ExpenseEntity
import com.spendwise.data.local.toDomain
import com.spendwise.data.local.toEntity
import com.spendwise.data.remote.MySqlExpenseDataSource
import com.spendwise.domain.model.Expense
import com.spendwise.domain.repository.ExpenseRepository
import com.spendwise.util.ExpenseSyncScheduler
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ExpenseRepositoryImpl @Inject constructor(
    private val dao: ExpenseDao,
    private val remoteDataSource: MySqlExpenseDataSource,
    private val syncScheduler: ExpenseSyncScheduler
) : ExpenseRepository {
    override fun observeExpenses(): Flow<List<Expense>> =
        dao.observeExpenses().map { expenses -> expenses.map { it.toDomain() } }

    override fun observePagedExpenses(filterState: com.spendwise.domain.model.ExpenseFilterState): Flow<PagingData<Expense>> =
        Pager(
            config = PagingConfig(
                pageSize = filterState.pageSize,
                prefetchDistance = filterState.pageSize / 2,
                enablePlaceholders = false
            ),
            pagingSourceFactory = { dao.pagingSourceRaw(com.spendwise.data.local.ExpenseQueryBuilder.buildPagingQuery(filterState)) }
        ).flow.map { pagingData -> pagingData.map { it.toDomain() } }

    override fun observePendingSyncCount(): Flow<Int> = dao.observePendingSyncCount()

    override suspend fun addExpense(expense: Expense) {
        val entity = expense.copy(isSynced = false).toEntity()
        val inserted = entity.copy(id = dao.insertExpense(entity).toInt())
        syncExpenseOrEnqueueRetry(inserted)
    }

    override suspend fun addExpensesBatch(expenses: List<Expense>) {
        val entities = expenses.map { it.copy(isSynced = false).toEntity() }
        dao.insertExpenses(entities)
        syncScheduler.enqueueImmediateSync()
    }

    override suspend fun updateExpense(expense: Expense) {
        val entity = expense.copy(isSynced = false).toEntity()
        dao.updateExpense(entity)
        syncExpenseOrEnqueueRetry(entity)
    }

    override suspend fun deleteExpense(expense: Expense) {
        val entity = expense.toEntity()
        dao.insertPendingDelete(ExpenseDeleteSyncEntity(localId = entity.id))
        dao.deleteExpense(entity)
        runCatching {
            remoteDataSource.deleteExpense(entity.id)
            dao.deletePendingDelete(entity.id)
        }.onFailure { error ->
            Log.w(TAG, "Immediate expense deletion sync failed; enqueuing retry.", error)
            syncScheduler.enqueueImmediateSync()
        }
    }

    override suspend fun syncPendingExpenses() {
        dao.getPendingSync().forEach { entity ->
            remoteDataSource.upsertExpense(entity)
            dao.updateExpense(entity.copy(isSynced = true))
        }
        dao.getPendingDeleteSync().forEach { delete ->
            remoteDataSource.deleteExpense(delete.localId)
            dao.deletePendingDelete(delete.localId)
        }
    }

    private suspend fun syncExpenseOrEnqueueRetry(entity: ExpenseEntity) {
        runCatching {
            remoteDataSource.upsertExpense(entity)
            dao.updateExpense(entity.copy(isSynced = true))
        }.onFailure { error ->
            Log.w(TAG, "Immediate expense sync failed; enqueuing retry.", error)
            syncScheduler.enqueueImmediateSync()
        }
    }

    private companion object {
        const val TAG = "ExpenseRepository"
    }
}
