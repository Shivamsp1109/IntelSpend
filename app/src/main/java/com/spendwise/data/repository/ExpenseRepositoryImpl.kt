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

    /**
     * Pushes everything waiting, and does not stop at the first thing that fails.
     *
     * This loop used to let a failure escape it. One row the server rejected —
     * a malformed field, a transient 500 — aborted the whole upload, and since
     * the sweep catches the exception and moves on to incomes, every expense
     * behind that row was skipped. The next attempt reached the same row and
     * stopped in the same place, so a single bad transaction could hold back an
     * unbounded number of good ones indefinitely.
     *
     * The successes are marked in one write rather than one write each. Every
     * update invalidates the expenses table, and everything observing it —
     * analytics, the recurring detector, the transaction list — recomputes on
     * each one. Marking two hundred rows individually meant two hundred rounds
     * of that during a single sync, which is what made the app stop responding
     * while a large import was going up.
     */
    override suspend fun syncPendingExpenses() {
        val uploaded = mutableListOf<Int>()

        for (entity in dao.getPendingSync()) {
            runCatching { remoteDataSource.upsertExpense(entity) }
                .onSuccess { uploaded += entity.id }
                .onFailure { Log.w(TAG, "Failed to sync expense id=${entity.id}; will retry.", it) }

            if (uploaded.size >= MARK_SYNCED_BATCH) {
                dao.markSynced(uploaded.toList())
                uploaded.clear()
            }
        }
        if (uploaded.isNotEmpty()) dao.markSynced(uploaded)

        for (delete in dao.getPendingDeleteSync()) {
            runCatching {
                remoteDataSource.deleteExpense(delete.localId)
                dao.deletePendingDelete(delete.localId)
            }.onFailure {
                Log.w(TAG, "Failed to sync delete for localId=${delete.localId}; will retry.", it)
            }
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

        /**
         * Rows marked synced per write.
         *
         * Small enough that a process killed mid-sweep re-uploads only a handful
         * — which is harmless, the upsert is keyed on the row's own id — and
         * large enough that a long import costs a few invalidations rather than
         * one per transaction.
         */
        const val MARK_SYNCED_BATCH = 50
    }
}
