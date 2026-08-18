package com.spendwise.domain.repository

import com.spendwise.domain.model.RiskAssessment
import kotlinx.coroutines.flow.Flow

interface RiskProfileRepository {
    /** The stored profile, confirmed or not — for the questionnaire screen itself. */
    fun observeProfile(): Flow<RiskAssessment?>

    /**
     * Only a profile the user has confirmed.
     *
     * Separate from [observeProfile] on purpose: anything that reasons about
     * risk must go through this one, so a half-finished questionnaire can never
     * be mistaken for an answer.
     */
    suspend fun getConfirmedProfile(): RiskAssessment?

    suspend fun save(profile: RiskAssessment)
    suspend fun clear()
    suspend fun syncPendingProfile()
}
