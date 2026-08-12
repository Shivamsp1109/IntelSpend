package com.spendwise.domain.repository

import com.spendwise.domain.model.Expense
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

interface ExpenseRepository {
    fun observeExpenses(): Flow<List<Expense>>
    fun observePagedExpenses(filterState: com.spendwise.domain.model.ExpenseFilterState): Flow<PagingData<Expense>>
    fun observePendingSyncCount(): Flow<Int>
    suspend fun addExpense(expense: Expense)
    suspend fun addExpensesBatch(expenses: List<Expense>)
    suspend fun updateExpense(expense: Expense)
    suspend fun deleteExpense(expense: Expense)
    suspend fun syncPendingExpenses()
}
