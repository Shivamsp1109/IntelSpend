package com.spendwise.presentation.viewmodel

import com.spendwise.MainDispatcherRule
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.ExpenseFilterState
import com.spendwise.domain.model.Income
import com.spendwise.domain.repository.AuthRepository
import com.spendwise.domain.repository.AuthUser
import com.spendwise.domain.repository.ExpenseRepository
import com.spendwise.domain.repository.FakeAnalyticsRepository
import com.spendwise.domain.repository.Gender
import com.spendwise.domain.repository.IncomeRepository
import com.spendwise.domain.usecase.AddIncomesUseCase
import com.spendwise.domain.usecase.UpdateIncomeUseCase
import com.spendwise.domain.usecase.DeleteIncomeUseCase
import com.spendwise.domain.usecase.GetBudgetStatusUseCase
import com.spendwise.domain.usecase.GetExpensesUseCase
import com.spendwise.domain.usecase.GetIncomesUseCase
import com.spendwise.domain.usecase.GetPendingSyncCountUseCase
import com.spendwise.domain.usecase.GetSmartInsightsUseCase
import com.spendwise.domain.usecase.GetSpendingSummaryUseCase
import com.spendwise.domain.usecase.SyncPendingExpensesUseCase
import com.spendwise.util.IncomePreferenceStore
import com.spendwise.util.NetworkMonitor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
            Expense(title = "Food", amount = 250.0, category = ExpenseCategory.FoodDining, date = System.currentTimeMillis()),
            Expense(title = "Travel", amount = 600.0, category = ExpenseCategory.Travel, date = System.currentTimeMillis())
        )
    )
    private val repository = FakeExpenseRepository(expenses)
    private val incomeRepository = FakeIncomeRepository()
    private val analyticsRepository = FakeAnalyticsRepository()

    @Test
    fun `summarizes home metrics from expenses`() = runTest {
        val viewModel = HomeViewModel(
            getExpensesUseCase = GetExpensesUseCase(repository),
            getPendingSyncCountUseCase = GetPendingSyncCountUseCase(repository),
            networkMonitor = FakeNetworkMonitor(),
            authRepository = FakeAuthRepository(),
            incomePreferenceStore = FakeIncomePreferenceStore(),
            getBudgetStatusUseCase = GetBudgetStatusUseCase(),
            getSpendingSummaryUseCase = GetSpendingSummaryUseCase(
                analyticsRepository,
                GetSmartInsightsUseCase()
            ),
            analyticsRepository = analyticsRepository,
            getIncomesUseCase = GetIncomesUseCase(incomeRepository),
            addIncomesUseCase = AddIncomesUseCase(incomeRepository),
            updateIncomeUseCase = UpdateIncomeUseCase(incomeRepository),
            deleteIncomeUseCase = DeleteIncomeUseCase(incomeRepository),
            syncPendingExpensesUseCase = SyncPendingExpensesUseCase(repository)
        )
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect()
        }
        advanceUntilIdle()

        val state = viewModel.uiState.value

        assertEquals(850.0, state.totalExpense, 0.0)
        assertEquals(850.0, state.todayExpense, 0.0)
        assertEquals(2, state.recentTransactions.size)
        assertEquals("Shivam", state.userName)
        assertEquals(10_000.0, state.monthlyIncome, 0.0)
        assertEquals(10_000.0, state.budgetStatus.monthlyBudget, 0.0)
        assertEquals(9, state.budgetStatus.roundedUsagePercent)
        collectJob.cancel()
    }

    private class FakeExpenseRepository(
        private val expenses: Flow<List<Expense>>
    ) : ExpenseRepository {
        override fun observeExpenses(): Flow<List<Expense>> = expenses
        override fun observePagedExpenses(filterState: ExpenseFilterState): Flow<PagingData<Expense>> = flowOf(PagingData.empty())
        override fun observePendingSyncCount(): Flow<Int> = flowOf(0)
        override suspend fun addExpense(expense: Expense) = Unit
        override suspend fun addExpensesBatch(expenses: List<Expense>) = Unit
        override suspend fun updateExpense(expense: Expense) = Unit
        override suspend fun deleteExpense(expense: Expense) = Unit
        override suspend fun syncPendingExpenses() = Unit
    }

    private class FakeIncomeRepository : IncomeRepository {
        private val incomes = MutableStateFlow<List<Income>>(emptyList())
        override fun observeIncomes(): Flow<List<Income>> = incomes
        override fun searchIncomes(query: String, source: String?): Flow<List<Income>> = emptyFlow()
        override fun observePendingSyncCount(): Flow<Int> = flowOf(0)
        override suspend fun addIncome(income: Income) {
            incomes.value = incomes.value + income
        }
        override suspend fun addIncomesBatch(incomes: List<Income>) {
            this.incomes.value = this.incomes.value + incomes
        }
        override suspend fun updateIncome(income: Income) = Unit
        override suspend fun deleteIncome(income: Income) = Unit
        override suspend fun syncPendingIncomes() = Unit
    }

    private class FakeNetworkMonitor : NetworkMonitor {
        override val isOnline: Flow<Boolean> = flowOf(true)
    }

    private class FakeAuthRepository : AuthRepository {
        override val currentUser: Flow<AuthUser?> = flowOf(
            AuthUser(
                id = "user-1",
                name = "Shivam",
                email = "shivam@example.com",
                gender = Gender.Male
            )
        )

        override suspend fun loginWithEmail(email: String, password: String): Result<Unit> = Result.success(Unit)
        override suspend fun registerWithEmail(
            email: String,
            password: String,
            name: String,
            gender: Gender,
            profileImageUri: android.net.Uri?
        ): Result<Unit> = Result.success(Unit)

        override suspend fun loginWithGoogleIdToken(idToken: String): Result<Unit> = Result.success(Unit)
        override suspend fun logout() = Unit
    }

    private class FakeIncomePreferenceStore : IncomePreferenceStore {
        private val income = MutableStateFlow(10_000.0)
        private val drafts = MutableStateFlow("{}")
        override val monthlyIncome: StateFlow<Double> = income
        override val incomeDrafts: StateFlow<String> = drafts
        override fun setMonthlyIncome(value: Double) {
            income.value = value
        }

        override fun setIncomeDrafts(value: String) {
            drafts.value = value
        }
    }
}
