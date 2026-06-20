package com.spendwise.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.ExpenseCategory
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
    private val appMetrics: AppMetrics
) : ViewModel() {
    private val _query = MutableStateFlow("")
    private val _category = MutableStateFlow<ExpenseCategory?>(null)
    val query = _query.asStateFlow()
    val category = _category.asStateFlow()

    val pagedExpenses = combine(_query, _category) { q, c -> q to c }
        .flatMapLatest { (q, c) -> getPagedExpensesUseCase(q, c?.label) }
        .cachedIn(viewModelScope)

    val uiState: StateFlow<ExpenseListUiState> = combine(_query, _category) { q, c ->
        ExpenseListUiState(query = q, selectedCategory = c)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExpenseListUiState())

    fun updateQuery(value: String) {
        _query.value = value
    }

    fun updateCategory(value: ExpenseCategory?) {
        _category.value = value
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

data class ExpenseListUiState(
    val query: String = "",
    val selectedCategory: ExpenseCategory? = null
)
