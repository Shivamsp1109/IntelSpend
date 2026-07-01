package com.spendwise.presentation.viewmodel

import android.util.Log
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
import com.spendwise.domain.usecase.SyncPendingExpensesUseCase
import com.spendwise.util.DateUtils
import com.spendwise.util.IncomePreferenceStore
import com.spendwise.util.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    getExpensesUseCase: GetExpensesUseCase,
    getPendingSyncCountUseCase: GetPendingSyncCountUseCase,
    networkMonitor: NetworkMonitor,
    authRepository: AuthRepository,
    private val incomePreferenceStore: IncomePreferenceStore,
    private val getBudgetStatusUseCase: GetBudgetStatusUseCase,
    private val getSmartInsightsUseCase: GetSmartInsightsUseCase,
    private val syncPendingExpensesUseCase: SyncPendingExpensesUseCase
) : ViewModel() {
    val incomeDrafts: StateFlow<String> = incomePreferenceStore.incomeDrafts

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
                monthExpenseTrendText = monthExpenseTrendText(expenses),
                todayExpense = expenses.filter { DateUtils.isToday(it.date) }.sumOf { it.amount },
                recentTransactions = expenses.take(5),
                budgetStatus = getBudgetStatusUseCase(expenses, monthlyIncome),
                insights = getSmartInsightsUseCase(expenses),
                networkSyncStatus = NetworkSyncStatus(isOnline, pendingSyncCount)
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            runCatching { syncPendingExpensesUseCase() }
                .onFailure { error ->
                    Log.w(TAG, "Foreground pending expense sync failed.", error)
                }
        }
    }

    fun updateMonthlyIncome(value: Double) {
        incomePreferenceStore.setMonthlyIncome(value)
    }

    fun addMonthlyIncome(value: Double) {
        incomePreferenceStore.setMonthlyIncome(uiState.value.monthlyIncome + value)
    }

    fun updateIncomeDrafts(value: String) {
        incomePreferenceStore.setIncomeDrafts(value)
    }

    private fun monthExpenseTrendText(expenses: List<Expense>): String {
        val thisMonth = expenses.filter { DateUtils.isThisMonth(it.date) }.sumOf { it.amount }
        val previousMonth = expenses.filter { DateUtils.isPreviousMonth(it.date) }.sumOf { it.amount }
        if (previousMonth == 0.0) {
            return if (thisMonth > 0.0) "New" else "0%"
        }
        val changePercent = ((thisMonth - previousMonth) / previousMonth) * 100.0
        val prefix = if (changePercent > 0.0) "+" else ""
        return "$prefix${changePercent.toInt()}%"
    }
}

data class HomeUiState(
    val userName: String = "User",
    val monthlyIncome: Double = 0.0,
    val totalExpense: Double = 0.0,
    val monthExpense: Double = 0.0,
    val monthExpenseTrendText: String = "0%",
    val todayExpense: Double = 0.0,
    val recentTransactions: List<Expense> = emptyList(),
    val budgetStatus: BudgetStatus = BudgetStatus(0.0, 0.0),
    val insights: List<Insight> = emptyList(),
    val networkSyncStatus: NetworkSyncStatus = NetworkSyncStatus(isOnline = false, pendingSyncCount = 0)
)

private const val TAG = "HomeViewModel"
