package com.spendwise.data.repository

import android.util.Log
import com.spendwise.data.local.RiskAssessmentDao
import com.spendwise.data.local.RiskAssessmentEntity
import com.spendwise.data.local.toDomain
import com.spendwise.data.local.toEntity
import com.spendwise.data.remote.MySqlRiskProfileDataSource
import com.spendwise.domain.model.RiskAssessment
import com.spendwise.domain.repository.RiskProfileRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RiskProfileRepositoryImpl @Inject constructor(
    private val dao: RiskAssessmentDao,
    private val remoteDataSource: MySqlRiskProfileDataSource
) : RiskProfileRepository {

    override fun observeProfile(): Flow<RiskAssessment?> =
        dao.observeProfile().map { it?.toDomain() }

    /**
     * The gate. Anything reasoning about risk comes through here, so a
     * half-finished questionnaire can never be read as an answer.
     */
    override suspend fun getConfirmedProfile(): RiskAssessment? =
        dao.getConfirmedProfile()?.toDomain()

    override suspend fun save(profile: RiskAssessment) {
        val entity = profile.toEntity().copy(isSynced = false)
        dao.upsertProfile(entity)
        syncOrLog(entity)
    }

    override suspend fun clear() {
        dao.deleteProfile()
        runCatching { remoteDataSource.deleteProfile() }
            .onFailure { Log.w(TAG, "Could not remove the risk profile remotely.", it) }
    }

    override suspend fun syncPendingProfile() {
        val pending = dao.getPendingSync()
        if (pending.isEmpty()) return

        val uploaded = mutableListOf<Int>()
        for (entity in pending) {
            runCatching { remoteDataSource.upsertProfile(entity) }
                .onSuccess { uploaded += entity.id }
                .onFailure { error ->
                    Log.w(TAG, "Failed to sync the risk profile; will retry.", error)
                }
        }
        if (uploaded.isNotEmpty()) dao.markSynced(uploaded)
    }

    private suspend fun syncOrLog(entity: RiskAssessmentEntity) {
        runCatching {
            remoteDataSource.upsertProfile(entity)
            dao.upsertProfile(entity.copy(isSynced = true))
        }.onFailure { error ->
            Log.w(TAG, "Immediate risk profile sync failed; SyncWorker will retry.", error)
        }
    }

    private companion object {
        const val TAG = "RiskProfileRepository"
    }
}
