package com.spendwise.domain.repository

import com.spendwise.domain.model.InsurancePolicy
import kotlinx.coroutines.flow.Flow

interface InsuranceRepository {
    fun observePolicies(): Flow<List<InsurancePolicy>>
    suspend fun getById(id: Int): InsurancePolicy?
    suspend fun addPolicy(policy: InsurancePolicy)
    suspend fun updatePolicy(policy: InsurancePolicy)
    suspend fun deletePolicy(policy: InsurancePolicy)
    suspend fun syncPendingPolicies()
}
