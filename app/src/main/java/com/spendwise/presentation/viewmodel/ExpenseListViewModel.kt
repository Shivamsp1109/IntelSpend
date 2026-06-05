package com.spendwise.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.usecase.DeleteExpenseUseCase
import com.spendwise.domain.usecase.SearchExpensesUseCase
import com.spendwise.domain.usecase.UpdateExpenseUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExpenseListViewModel @Inject constructor(
    private val searchExpensesUseCase: SearchExpensesUseCase,
    private val deleteExpenseUseCase: DeleteExpenseUseCase,
    private val updateExpenseUseCase: UpdateExpenseUseCase
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<ExpenseCategory?>(null)

    val uiState: StateFlow<ExpenseListUiState> = combine(query, category) { q, c -> q to c }
        .flatMapLatest { (q, c) -> searchExpensesUseCase(q, c?.label) }
        .combine(query) { expenses, q -> ExpenseListUiState(expenses = expenses, query = q, selectedCategory = category.value) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExpenseListUiState())

    fun updateQuery(value: String) {
        query.value = value
    }

    fun updateCategory(value: ExpenseCategory?) {
        category.value = value
    }

    fun delete(expense: Expense) {
        viewModelScope.launch {
            deleteExpenseUseCase(expense)
        }
    }

    fun update(expense: Expense) {
        viewModelScope.launch {
            updateExpenseUseCase(expense)
        }
    }
}

data class ExpenseListUiState(
    val expenses: List<Expense> = emptyList(),
    val query: String = "",
    val selectedCategory: ExpenseCategory? = null
)
