package com.spendwise.data.repository

import com.spendwise.data.local.ExpenseDao
import com.spendwise.data.local.toDomain
import com.spendwise.data.local.toEntity
import com.spendwise.data.remote.FirestoreExpenseDataSource
import com.spendwise.domain.model.Expense
import com.spendwise.domain.repository.ExpenseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ExpenseRepositoryImpl @Inject constructor(
    private val dao: ExpenseDao,
    private val remoteDataSource: FirestoreExpenseDataSource
) : ExpenseRepository {
    override fun observeExpenses(): Flow<List<Expense>> =
        dao.observeExpenses().map { expenses -> expenses.map { it.toDomain() } }

    override fun searchExpenses(query: String, category: String?): Flow<List<Expense>> =
        dao.searchExpenses(query, category).map { expenses -> expenses.map { it.toDomain() } }

    override suspend fun addExpense(expense: Expense) {
        dao.insertExpense(expense.copy(isSynced = false).toEntity())
    }

    override suspend fun updateExpense(expense: Expense) {
        dao.updateExpense(expense.copy(isSynced = false).toEntity())
    }

    override suspend fun deleteExpense(expense: Expense) {
        dao.deleteExpense(expense.toEntity())
    }

    override suspend fun syncPendingExpenses() {
        dao.getPendingSync().forEach { entity ->
            remoteDataSource.upsertExpense(entity)
            dao.updateExpense(entity.copy(isSynced = true))
        }
    }
}
