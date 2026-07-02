package com.spendwise.data.repository

import android.util.Log
import com.spendwise.data.local.GoalDao
import com.spendwise.data.local.toDomain
import com.spendwise.data.local.toEntity
import com.spendwise.data.remote.MySqlGoalDataSource
import com.spendwise.domain.model.Goal
import com.spendwise.domain.repository.GoalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class GoalRepositoryImpl @Inject constructor(
    private val dao: GoalDao,
    private val remoteDataSource: MySqlGoalDataSource
) : GoalRepository {

    override fun observeGoals(): Flow<List<Goal>> =
        dao.observeGoals().map { list -> list.map { it.toDomain() } }

    override suspend fun getGoalById(id: Int): Goal? =
        dao.getGoalById(id)?.toDomain()

    override suspend fun addGoal(goal: Goal) {
        val entity = goal.copy(isSynced = false).toEntity()
        val inserted = entity.copy(id = dao.insertGoal(entity).toInt())
        syncGoalOrLog(inserted)
    }

    override suspend fun updateGoal(goal: Goal) {
        val entity = goal.copy(isSynced = false).toEntity()
        dao.updateGoal(entity)
        syncGoalOrLog(entity)
    }

    override suspend fun deleteGoal(goal: Goal) {
        dao.deleteGoal(goal.toEntity())
    }

    override suspend fun syncPendingGoals() {
        dao.getPendingSync().forEach { entity ->
            runCatching {
                remoteDataSource.upsertGoal(entity)
                dao.updateGoal(entity.copy(isSynced = true))
            }.onFailure { error ->
                Log.w(TAG, "Failed to sync goal id=${entity.id}; will retry.", error)
            }
        }
    }

    private suspend fun syncGoalOrLog(entity: com.spendwise.data.local.GoalEntity) {
        runCatching {
            remoteDataSource.upsertGoal(entity)
            dao.updateGoal(entity.copy(isSynced = true))
        }.onFailure { error ->
            Log.w(TAG, "Immediate goal sync failed; will be retried by SyncWorker.", error)
        }
    }

    private companion object {
        const val TAG = "GoalRepository"
    }
}

