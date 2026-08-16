package com.spendwise.data.repository

import android.util.Log
import com.spendwise.data.local.RecurringEntity
import com.spendwise.data.local.RecurringEntryDao
import com.spendwise.data.local.RecurringExpenseCrossRef
import com.spendwise.data.local.toDomain
import com.spendwise.data.local.toEntity
import com.spendwise.data.remote.MySqlRecurringDataSource
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.repository.RecurringEntryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class RecurringEntryRepositoryImpl @Inject constructor(
    private val dao: RecurringEntryDao,
    private val remoteDataSource: MySqlRecurringDataSource
) : RecurringEntryRepository {

    override fun observeRecurring(): Flow<List<RecurringEntry>> =
        dao.observeRecurring().map { list -> list.map { it.toDomain() } }

    override suspend fun getById(id: Int): RecurringEntry? =
        dao.getById(id)?.toDomain()

    override suspend fun addRecurring(entry: RecurringEntry) {
        val entity = entry.toEntity().copy(isSynced = false)
        val inserted = entity.copy(id = dao.insertRecurring(entity).toInt())
        syncRecurringOrLog(inserted)
    }

    override suspend fun updateRecurring(entry: RecurringEntry) {
        val entity = entry.toEntity().copy(isSynced = false)
        dao.updateRecurring(entity)
        syncRecurringOrLog(entity)
    }

    override suspend fun deleteRecurring(entry: RecurringEntry) {
        // CASCADE on the FK handles cross-ref cleanup automatically.
        dao.deleteRecurring(entry.toEntity())
    }

    override suspend fun linkExpense(recurringId: Int, expenseId: Int) {
        dao.linkExpense(RecurringExpenseCrossRef(recurringId, expenseId))
        runCatching {
            remoteDataSource.linkExpense(recurringId, expenseId)
        }.onFailure { error ->
            Log.w(TAG, "Failed to sync recurring link ($recurringId→$expenseId).", error)
        }
    }

    override suspend fun unlinkExpense(recurringId: Int, expenseId: Int) {
        dao.unlinkExpense(RecurringExpenseCrossRef(recurringId, expenseId))
        runCatching {
            remoteDataSource.unlinkExpense(recurringId, expenseId)
        }.onFailure { error ->
            Log.w(TAG, "Failed to sync recurring unlink ($recurringId→$expenseId).", error)
        }
    }

    /** See ExpenseRepositoryImpl.syncPendingExpenses — same shape, same reason. */
    override suspend fun syncPendingRecurring() {
        val uploaded = mutableListOf<Int>()
        for (entity in dao.getPendingSync()) {
            runCatching { remoteDataSource.upsertRecurring(entity) }
                .onSuccess { uploaded += entity.id }
                .onFailure { error ->
                    Log.w(TAG, "Failed to sync recurring id=${entity.id}; will retry.", error)
                }

            if (uploaded.size >= MARK_SYNCED_BATCH) {
                dao.markSynced(uploaded.toList())
                uploaded.clear()
            }
        }
        if (uploaded.isNotEmpty()) dao.markSynced(uploaded)
    }

    private suspend fun syncRecurringOrLog(entity: RecurringEntity) {
        runCatching {
            remoteDataSource.upsertRecurring(entity)
            dao.updateRecurring(entity.copy(isSynced = true))
        }.onFailure { error ->
            Log.w(TAG, "Immediate recurring sync failed; will be retried by SyncWorker.", error)
        }
    }

    private companion object {
        const val TAG = "RecurringEntryRepository"

        /** See ExpenseRepositoryImpl.MARK_SYNCED_BATCH. */
        const val MARK_SYNCED_BATCH = 50
    }
}

