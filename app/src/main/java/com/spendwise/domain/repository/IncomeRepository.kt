package com.spendwise.domain.repository

import com.spendwise.domain.model.Income
import kotlinx.coroutines.flow.Flow

interface IncomeRepository {
    fun observeIncomes(): Flow<List<Income>>
    fun searchIncomes(query: String, source: String?): Flow<List<Income>>
    fun observePendingSyncCount(): Flow<Int>
    suspend fun addIncome(income: Income)
    suspend fun updateIncome(income: Income)
    suspend fun deleteIncome(income: Income)
    suspend fun syncPendingIncomes()
}
