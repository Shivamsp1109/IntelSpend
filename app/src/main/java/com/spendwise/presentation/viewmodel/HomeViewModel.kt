package com.spendwise.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.domain.model.AnalyticsPeriod
import com.spendwise.domain.model.BudgetStatus
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.Income
import com.spendwise.domain.model.Insight
import com.spendwise.domain.model.NetworkSyncStatus
import com.spendwise.domain.repository.AnalyticsRepository
import com.spendwise.domain.repository.AuthRepository
import com.spendwise.domain.usecase.AddIncomesUseCase
import com.spendwise.domain.usecase.UpdateIncomeUseCase
import com.spendwise.domain.usecase.DeleteIncomeUseCase
import com.spendwise.domain.usecase.GetBudgetStatusUseCase
import com.spendwise.domain.usecase.GetExpensesUseCase
import com.spendwise.domain.usecase.GetIncomesUseCase
import com.spendwise.domain.usecase.GetPendingSyncCountUseCase
import com.spendwise.domain.usecase.GetSpendingSummaryUseCase
import com.spendwise.domain.usecase.SyncPendingExpensesUseCase
import com.spendwise.util.DateUtils
import com.spendwise.util.IncomePreferenceStore
import com.spendwise.util.NetworkMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.spendwise.domain.repository.AuthUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    getExpensesUseCase: GetExpensesUseCase,
    getPendingSyncCountUseCase: GetPendingSyncCountUseCase,
    networkMonitor: NetworkMonitor,
    authRepository: AuthRepository,
    private val incomePreferenceStore: IncomePreferenceStore,
    private val getBudgetStatusUseCase: GetBudgetStatusUseCase,
    private val getSpendingSummaryUseCase: GetSpendingSummaryUseCase,
    private val analyticsRepository: AnalyticsRepository,
    private val getIncomesUseCase: GetIncomesUseCase,
    private val addIncomesUseCase: AddIncomesUseCase,
    private val updateIncomeUseCase: UpdateIncomeUseCase,
    private val deleteIncomeUseCase: DeleteIncomeUseCase,
    private val syncPendingExpensesUseCase: SyncPendingExpensesUseCase
) : ViewModel() {
    val incomeDrafts: StateFlow<String> = incomePreferenceStore.incomeDrafts

    val currentMonthIncomes: StateFlow<List<Income>> = getIncomesUseCase()
        .combine(incomeDrafts) { list, _ -> list.filter { DateUtils.isThisMonth(it.date) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Insights for the current month, from the same engine the analytics screen
     * uses, so the two screens can never tell the user different things about
     * the same month.
     *
     * Driven off the repository's change signal rather than folded into the
     * combine below. Those flows emit on network and auth changes too, and each
     * emission here costs a round of aggregate queries — there is no reason to
     * recompute insights because connectivity flickered.
     */
    private val monthlyInsights: Flow<List<Insight>> = analyticsRepository.changes()
        // A sync writes many rows in a burst and this fires on every one of
        // them, each time re-running the aggregate queries behind an insight.
        // Settling first turns a hundred redundant rounds into one.
        .debounce(SETTLE_MILLIS)
        .mapLatest {
            runCatching { getSpendingSummaryUseCase(AnalyticsPeriod.thisMonth()).insights }
                .getOrElse { error ->
                    Log.w(TAG, "Could not load home insights.", error)
                    emptyList()
                }
        }

    val uiState: StateFlow<HomeUiState> = combine(
        combine(
            getExpensesUseCase(),
            getIncomesUseCase(),
            networkMonitor.isOnline,
            getPendingSyncCountUseCase(),
            authRepository.currentUser
        ) { expenses, incomes, isOnline, pendingSyncCount, user ->
            RawHomeInputs(expenses, incomes, isOnline, pendingSyncCount, user)
        }
            // expenses and incomes are the whole table, unpaged, and every sync
            // write re-emits it — a burst of a few hundred rows recomputed this
            // several hundred times in a row for one visible result. Settling
            // first collapses a burst to one pass.
            .debounce(SETTLE_MILLIS)
            .map { (expenses, incomes, isOnline, pendingSyncCount, user) ->
                val monthlyIncome = incomes.filter { DateUtils.isThisMonth(it.date) }.sumOf { it.amount }
                HomeUiState(
                    userName = user?.name?.takeIf { it.isNotBlank() } ?: "User",
                    monthlyIncome = monthlyIncome,
                    totalExpense = expenses.sumOf { it.amount },
                    monthExpense = expenses.filter { DateUtils.isThisMonth(it.date) }.sumOf { it.amount },
                    monthExpenseTrendText = monthExpenseTrendText(expenses),
                    todayExpense = expenses.filter { DateUtils.isToday(it.date) }.sumOf { it.amount },
                    recentTransactions = expenses.take(5),
                    budgetStatus = getBudgetStatusUseCase(expenses, monthlyIncome),
                    networkSyncStatus = NetworkSyncStatus(isOnline, pendingSyncCount)
                )
            },
        monthlyInsights
    ) { state, insights -> state.copy(insights = insights) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            val legacyIncome = incomePreferenceStore.monthlyIncome.first()
            if (legacyIncome > 0.0) {
                addIncomesUseCase(listOf(Income(
                    title = "Legacy Income",
                    amount = legacyIncome,
                    currency = com.spendwise.domain.model.Currency.INR,
                    source = com.spendwise.domain.model.IncomeSource.MISCELLANEOUS,
                    date = System.currentTimeMillis()
                )))
                incomePreferenceStore.setMonthlyIncome(0.0)
            }
            runCatching { syncPendingExpensesUseCase() }
                .onFailure { error ->
                    Log.w(TAG, "Foreground pending expense sync failed.", error)
                }
        }
    }

    fun addIncomes(sheetIncomes: List<Income>) {
        viewModelScope.launch {
            val toAdd = sheetIncomes.filter { it.amount > 0 }
            if (toAdd.isNotEmpty()) {
                addIncomesUseCase(toAdd)
            }
        }
    }

    fun saveIncomes(updatedIncomes: List<Income>) {
        viewModelScope.launch {
            val existing = currentMonthIncomes.value
            val updatedIds = updatedIncomes.map { it.id }.filter { it > 0 }.toSet()
            val toDelete = existing.filter { it.id !in updatedIds }

            for (income in toDelete) {
                deleteIncomeUseCase(income)
            }

            for (income in updatedIncomes) {
                if (income.id > 0) {
                    // Update
                    updateIncomeUseCase(income)
                } else if (income.amount > 0) {
                    // Insert
                    addIncomesUseCase(listOf(income))
                }
            }
        }
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

/** Long enough to let a burst of writes finish, short enough to still feel live. */
private const val SETTLE_MILLIS = 300L

/** What the raw combine carries before the heavy computation below it. */
private data class RawHomeInputs(
    val expenses: List<Expense>,
    val incomes: List<Income>,
    val isOnline: Boolean,
    val pendingSyncCount: Int,
    val user: AuthUser?
)
