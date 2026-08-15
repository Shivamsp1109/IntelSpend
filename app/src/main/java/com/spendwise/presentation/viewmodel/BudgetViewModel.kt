package com.spendwise.presentation.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.data.local.CategoryBudgetDao
import com.spendwise.data.local.CategoryBudgetEntity
import com.spendwise.data.remote.MySqlBudgetDataSource
import com.spendwise.util.SyncScheduler
import com.spendwise.domain.model.BudgetMonth
import com.spendwise.domain.model.CategoryBudgetStatus
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.usecase.GetCategoryBudgetStatusUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the budgets screen.
 *
 * The month is resolved once when the screen opens rather than on every
 * recomposition, so a session that spans midnight on the 1st does not silently
 * start measuring against a different month halfway through.
 */
@HiltViewModel
class BudgetViewModel @Inject constructor(
    private val budgetDao: CategoryBudgetDao,
    private val budgetDataSource: MySqlBudgetDataSource,
    private val syncScheduler: SyncScheduler,
    getCategoryBudgetStatus: GetCategoryBudgetStatusUseCase
) : ViewModel() {

    private val month = BudgetMonth.containing()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val uiState: StateFlow<BudgetUiState> = getCategoryBudgetStatus(month)
        .map { statuses ->
            BudgetUiState(
                statuses = statuses,
                monthLabel = month.key,
                totalLimit = statuses.sumOf { it.limit },
                totalSpent = statuses.sumOf { it.spent },
                isLoaded = true
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetUiState())

    /**
     * Sets or replaces a limit.
     *
     * An existing budget is updated in place rather than replaced, so the alert
     * history survives — otherwise adjusting a limit mid-month would re-announce
     * a threshold the user has already been told about.
     */
    fun setLimit(category: ExpenseCategory, limitText: String, currency: Currency = Currency.INR) {
        viewModelScope.launch {
            val limit = limitText.toDoubleOrNull()
            if (limit == null || limit <= 0) {
                _message.value = "Enter an amount above zero."
                return@launch
            }

            val existing = budgetDao.getBudgetFor(category.label, currency.code)
            if (existing == null) {
                budgetDao.insertBudget(
                    CategoryBudgetEntity(
                        category = category.label,
                        monthlyLimit = limit,
                        currency = currency.code
                    )
                )
            } else {
                budgetDao.updateBudget(
                    existing.copy(monthlyLimit = limit, isSynced = false)
                )
            }
            nudgeSync()
            _message.value = "${category.label} budget set."
        }
    }

    fun removeBudget(status: CategoryBudgetStatus) {
        viewModelScope.launch {
            val existing = budgetDao.getBudgetFor(status.category.label, status.currency.code)
                ?: return@launch
            budgetDao.deleteBudget(existing)
            // Removed from the server directly rather than through the sweep: a
            // deletion is an absence, and the sweep only ever finds rows that
            // are present.
            runCatching { budgetDataSource.deleteBudget(existing.id) }
                .onFailure { Log.w(TAG, "Could not remove the budget remotely.", it) }
            _message.value = "Removed the ${status.category.label} budget."
        }
    }

    private fun nudgeSync() {
        runCatching { syncScheduler.enqueueImmediateSync() }
            .onFailure { Log.w(TAG, "Could not schedule a sync after a budget change.", it) }
    }

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        const val TAG = "BudgetViewModel"
    }
}

data class BudgetUiState(
    val statuses: List<CategoryBudgetStatus> = emptyList(),
    val monthLabel: String = "",
    val totalLimit: Double = 0.0,
    val totalSpent: Double = 0.0,
    val isLoaded: Boolean = false
) {
    /** Categories still available to budget, so the picker never offers a duplicate. */
    val unbudgeted: List<ExpenseCategory>
        get() = ExpenseCategory.entries.filterNot { category ->
            statuses.any { it.category == category }
        }
}
