package com.spendwise.presentation.viewmodel

import com.spendwise.MainDispatcherRule
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.repository.ExpenseRepository
import com.spendwise.domain.usecase.GetBudgetStatusUseCase
import com.spendwise.domain.usecase.GetExpensesUseCase
import com.spendwise.domain.usecase.GetPendingSyncCountUseCase
import com.spendwise.domain.usecase.GetSmartInsightsUseCase
import com.spendwise.util.NetworkMonitor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import androidx.paging.PagingData
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val expenses = MutableStateFlow(
        listOf(
            Expense(title = "Food", amount = 250.0, category = ExpenseCategory.Food, date = System.currentTimeMillis()),
            Expense(title = "Travel", amount = 600.0, category = ExpenseCategory.Travel, date = System.currentTimeMillis())
        )
    )
    private val repository = FakeExpenseRepository(expenses)

    @Test
    fun `summarizes home metrics from expenses`() = runTest {
        val viewModel = HomeViewModel(
            getExpensesUseCase = GetExpensesUseCase(repository),
            getPendingSyncCountUseCase = GetPendingSyncCountUseCase(repository),
            networkMonitor = FakeNetworkMonitor(),
            getBudgetStatusUseCase = GetBudgetStatusUseCase(),
            getSmartInsightsUseCase = GetSmartInsightsUseCase()
        )
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        advanceUntilIdle()

        val state = viewModel.uiState.value

        assertEquals(850.0, state.totalExpense, 0.0)
        assertEquals(850.0, state.todayExpense, 0.0)
        assertEquals(2, state.recentTransactions.size)
        collectJob.cancel()
    }

    private class FakeExpenseRepository(
        private val expenses: Flow<List<Expense>>
    ) : ExpenseRepository {
        override fun observeExpenses(): Flow<List<Expense>> = expenses
        override fun observePagedExpenses(query: String, category: String?): Flow<PagingData<Expense>> = flowOf(PagingData.empty())
        override fun searchExpenses(query: String, category: String?): Flow<List<Expense>> = emptyFlow()
        override fun observePendingSyncCount(): Flow<Int> = flowOf(0)
        override suspend fun addExpense(expense: Expense) = Unit
        override suspend fun updateExpense(expense: Expense) = Unit
        override suspend fun deleteExpense(expense: Expense) = Unit
        override suspend fun syncPendingExpenses() = Unit
    }

    private class FakeNetworkMonitor : NetworkMonitor {
        override val isOnline: Flow<Boolean> = flowOf(true)
    }
}
