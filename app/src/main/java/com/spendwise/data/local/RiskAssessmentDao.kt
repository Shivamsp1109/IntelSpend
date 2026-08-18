package com.spendwise.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RiskAssessmentDao {

    @Query("SELECT * FROM risk_assessments WHERE id = :id")
    fun observeProfile(id: Int = RiskAssessmentEntity.SINGLETON_ID): Flow<RiskAssessmentEntity?>

    @Query("SELECT * FROM risk_assessments WHERE id = :id")
    suspend fun getProfile(id: Int = RiskAssessmentEntity.SINGLETON_ID): RiskAssessmentEntity?

    /**
     * Only a confirmed profile, for anything that reasons over risk.
     *
     * The gate expressed in SQL rather than left to each caller to remember. An
     * unconfirmed profile is a draft, and treating a draft as an answer would
     * apply a risk level the user never agreed to.
     */
    @Query("SELECT * FROM risk_assessments WHERE userConfirmed = 1 LIMIT 1")
    suspend fun getConfirmedProfile(): RiskAssessmentEntity?

    @Query("SELECT * FROM risk_assessments WHERE isSynced = 0")
    suspend fun getPendingSync(): List<RiskAssessmentEntity>

    @Query("UPDATE risk_assessments SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Int>)

    /** Replaces on conflict: a person has one current profile, not a history. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: RiskAssessmentEntity)

    @Query("DELETE FROM risk_assessments")
    suspend fun deleteProfile()
}
