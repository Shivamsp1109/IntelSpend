package com.spendwise.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.domain.model.BudgetStatus
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.Insight
import com.spendwise.domain.model.NetworkSyncStatus
import com.spendwise.domain.repository.AuthRepository
import com.spendwise.domain.usecase.GetBudgetStatusUseCase
import com.spendwise.domain.usecase.GetExpensesUseCase
import com.spendwise.domain.usecase.GetPendingSyncCountUseCase
import com.spendwise.domain.usecase.GetSmartInsightsUseCase
import com.spendwise.util.DateUtils
import com.spendwise.util.IncomePreferenceStore
import com.spendwise.util.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class HomeViewModel @Inject constructor(
    getExpensesUseCase: GetExpensesUseCase,
    getPendingSyncCountUseCase: GetPendingSyncCountUseCase,
    networkMonitor: NetworkMonitor,
    authRepository: AuthRepository,
    private val incomePreferenceStore: IncomePreferenceStore,
    private val getBudgetStatusUseCase: GetBudgetStatusUseCase,
    private val getSmartInsightsUseCase: GetSmartInsightsUseCase
) : ViewModel() {
    val uiState: StateFlow<HomeUiState> = combine(
        getExpensesUseCase(),
        networkMonitor.isOnline,
        getPendingSyncCountUseCase(),
        authRepository.currentUser,
        incomePreferenceStore.monthlyIncome
    ) { expenses, isOnline, pendingSyncCount, user, monthlyIncome ->
            HomeUiState(
                userName = user?.name?.takeIf { it.isNotBlank() } ?: "User",
                monthlyIncome = monthlyIncome,
                totalExpense = expenses.sumOf { it.amount },
                monthExpense = expenses.filter { DateUtils.isThisMonth(it.date) }.sumOf { it.amount },
                todayExpense = expenses.filter { DateUtils.isToday(it.date) }.sumOf { it.amount },
                recentTransactions = expenses.take(5),
                budgetStatus = getBudgetStatusUseCase(expenses, 15_000.0),
                insights = getSmartInsightsUseCase(expenses),
                networkSyncStatus = NetworkSyncStatus(isOnline, pendingSyncCount)
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun updateMonthlyIncome(value: Double) {
        incomePreferenceStore.setMonthlyIncome(value)
    }
}

data class HomeUiState(
    val userName: String = "User",
    val monthlyIncome: Double = 0.0,
    val totalExpense: Double = 0.0,
    val monthExpense: Double = 0.0,
    val todayExpense: Double = 0.0,
    val recentTransactions: List<Expense> = emptyList(),
    val budgetStatus: BudgetStatus = BudgetStatus(15_000.0, 0.0),
    val insights: List<Insight> = emptyList(),
    val networkSyncStatus: NetworkSyncStatus = NetworkSyncStatus(isOnline = false, pendingSyncCount = 0)
)
