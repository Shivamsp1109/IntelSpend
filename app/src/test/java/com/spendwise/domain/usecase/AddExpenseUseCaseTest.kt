package com.spendwise.domain.usecase

import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.ExpenseFilterState
import com.spendwise.domain.repository.ExpenseRepository
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.Mockito.verify
import org.mockito.Mockito.mock

class AddExpenseUseCaseTest {
    private val repository = mock(ExpenseRepository::class.java)
    private val useCase = AddExpenseUseCase(repository)

    @Test
    fun `adds expense through repository`() = runTest {
        val expense = Expense(
            title = "Lunch",
            amount = 250.0,
            category = ExpenseCategory.Food,
            date = 1_700_000_000_000
        )

        useCase(expense)

        verify(repository).addExpense(expense)
    }

    @Suppress("unused")
    private class EmptyRepository : ExpenseRepository {
        override fun observeExpenses(): Flow<List<Expense>> = emptyFlow()
        override fun observePagedExpenses(filterState: ExpenseFilterState): Flow<PagingData<Expense>> = flowOf(PagingData.empty())
        override fun observePendingSyncCount(): Flow<Int> = flowOf(0)
        override suspend fun addExpense(expense: Expense) = Unit
        override suspend fun addExpensesBatch(expenses: List<Expense>) = Unit
        override suspend fun updateExpense(expense: Expense) = Unit
        override suspend fun deleteExpense(expense: Expense) = Unit
        override suspend fun syncPendingExpenses() = Unit
    }
}
