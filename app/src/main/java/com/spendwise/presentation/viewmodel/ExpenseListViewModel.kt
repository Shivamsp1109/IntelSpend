package com.spendwise.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.spendwise.domain.model.Expense
import com.spendwise.domain.usecase.DeleteExpenseUseCase
import com.spendwise.domain.usecase.GetPagedExpensesUseCase
import com.spendwise.domain.usecase.UpdateExpenseUseCase
import com.spendwise.util.AppMetrics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExpenseListViewModel @Inject constructor(
    private val getPagedExpensesUseCase: GetPagedExpensesUseCase,
    private val deleteExpenseUseCase: DeleteExpenseUseCase,
    private val updateExpenseUseCase: UpdateExpenseUseCase,
    private val appMetrics: AppMetrics,
    private val dao: com.spendwise.data.local.ExpenseDao
) : ViewModel() {

    private val _filterState = MutableStateFlow(com.spendwise.domain.model.ExpenseFilterState())
    val filterState = _filterState.asStateFlow()

    val pagedExpenses = _filterState
        .flatMapLatest { state -> getPagedExpensesUseCase(state) }
        .cachedIn(viewModelScope)

    val distinctTitles = dao.getDistinctTitles().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val distinctCategories = dao.getDistinctCategories().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val distinctMerchants = dao.getDistinctMerchants().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val distinctCurrencies = dao.getDistinctCurrencies().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun updateFilterState(update: (com.spendwise.domain.model.ExpenseFilterState) -> com.spendwise.domain.model.ExpenseFilterState) {
        _filterState.value = update(_filterState.value)
    }

    fun delete(expense: Expense) {
        viewModelScope.launch {
            deleteExpenseUseCase(expense)
            appMetrics.logExpenseDeleted(expense.category.label)
        }
    }

    fun update(expense: Expense) {
        viewModelScope.launch {
            updateExpenseUseCase(expense)
        }
    }
}
