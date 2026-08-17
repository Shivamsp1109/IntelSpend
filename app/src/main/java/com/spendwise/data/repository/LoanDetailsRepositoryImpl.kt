package com.spendwise.data.repository

import android.util.Log
import com.spendwise.data.local.LoanDetailsDao
import com.spendwise.data.local.LoanDetailsEntity
import com.spendwise.data.local.toDomain
import com.spendwise.data.local.toEntity
import com.spendwise.data.remote.MySqlLoanDetailsDataSource
import com.spendwise.domain.model.LoanDetails
import com.spendwise.domain.repository.LoanDetailsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LoanDetailsRepositoryImpl @Inject constructor(
    private val dao: LoanDetailsDao,
    private val remoteDataSource: MySqlLoanDetailsDataSource
) : LoanDetailsRepository {

    override fun observeAll(): Flow<List<LoanDetails>> =
        dao.observeLoanDetails().map { list -> list.map { it.toDomain() } }

    override fun observeForRecurring(recurringId: Int): Flow<LoanDetails?> =
        dao.observeForRecurring(recurringId).map { it?.toDomain() }

    override suspend fun getForRecurring(recurringId: Int): LoanDetails? =
        dao.getForRecurring(recurringId)?.toDomain()

    /**
     * One set of terms per commitment, so saving replaces rather than adds.
     *
     * The existing row's id is carried over deliberately: inserting a second row
     * for the same loan would make every total that reads them ambiguous, and
     * the unique index would reject it anyway.
     */
    override suspend fun save(details: LoanDetails) {
        val existing = dao.getForRecurring(details.recurringId)
        val entity = details.toEntity().copy(
            id = existing?.id ?: 0,
            isSynced = false
        )

        val saved = if (existing == null) {
            entity.copy(id = dao.insertLoanDetails(entity).toInt())
        } else {
            dao.updateLoanDetails(entity)
            entity
        }
        syncOrLog(saved)
    }

    override suspend fun delete(details: LoanDetails) {
        dao.deleteLoanDetails(details.toEntity())
        runCatching { remoteDataSource.deleteLoanDetails(details.recurringId) }
            .onFailure {
                Log.w(TAG, "Could not remove loan terms for ${details.recurringId} remotely.", it)
            }
    }

    /** See ExpenseRepositoryImpl.syncPendingExpenses — same shape, same reason. */
    override suspend fun syncPendingLoanDetails() {
        val uploaded = mutableListOf<Int>()
        for (entity in dao.getPendingSync()) {
            runCatching { remoteDataSource.upsertLoanDetails(entity) }
                .onSuccess { uploaded += entity.id }
                .onFailure { error ->
                    Log.w(TAG, "Failed to sync loan terms id=${entity.id}; will retry.", error)
                }

            if (uploaded.size >= MARK_SYNCED_BATCH) {
                dao.markSynced(uploaded.toList())
                uploaded.clear()
            }
        }
        if (uploaded.isNotEmpty()) dao.markSynced(uploaded)
    }

    private suspend fun syncOrLog(entity: LoanDetailsEntity) {
        runCatching {
            remoteDataSource.upsertLoanDetails(entity)
            dao.updateLoanDetails(entity.copy(isSynced = true))
        }.onFailure { error ->
            Log.w(TAG, "Immediate loan-terms sync failed; SyncWorker will retry.", error)
        }
    }

    private companion object {
        const val TAG = "LoanDetailsRepository"
        const val MARK_SYNCED_BATCH = 50
    }
}
