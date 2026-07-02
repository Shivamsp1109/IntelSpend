package com.spendwise.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.ExpenseSource
import com.spendwise.domain.usecase.AddExpenseUseCase
import com.spendwise.util.AppMetrics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AddExpenseViewModel @Inject constructor(
    private val addExpenseUseCase: AddExpenseUseCase,
    private val appMetrics: AppMetrics
) : ViewModel() {
    private val _uiState = MutableStateFlow(AddExpenseUiState())
    val uiState = _uiState.asStateFlow()

    fun updateTitle(value: String) {
        _uiState.value = _uiState.value.copy(title = value, error = null)
    }

    fun updateAmount(value: String) {
        _uiState.value = _uiState.value.copy(amount = value, error = null)
    }

    fun updateCategory(value: ExpenseCategory) {
        _uiState.value = _uiState.value.copy(category = value)
    }

    fun updateDate(value: Long) {
        _uiState.value = _uiState.value.copy(date = value)
    }

    fun updateMerchant(value: String) {
        _uiState.value = _uiState.value.copy(merchant = value)
    }

    fun updateCurrency(value: Currency) {
        _uiState.value = _uiState.value.copy(currency = value)
    }

    fun clearForm() {
        _uiState.value = AddExpenseUiState()
    }

    fun save(onSaved: () -> Unit) {
        val state = _uiState.value
        val amount = state.amount.toDoubleOrNull()
        if (state.title.isBlank() || amount == null || amount <= 0.0) {
            _uiState.value = state.copy(error = "Enter a valid title and amount.")
            return
        }
        viewModelScope.launch {
            addExpenseUseCase(
                Expense(
                    title = state.title.trim(),
                    amount = amount,
                    category = state.category,
                    date = state.date,
                    merchant = state.merchant.trimOrNull(),
                    currency = state.currency,
                    source = ExpenseSource.MANUAL
                )
            )
            appMetrics.logExpenseSaved(state.category.label, amount)
            onSaved()
        }
    }

    private fun String.trimOrNull() = trim().ifBlank { null }
}

data class AddExpenseUiState(
    val title: String = "",
    val amount: String = "",
    val category: ExpenseCategory = ExpenseCategory.Food,
    val date: Long = System.currentTimeMillis(),
    val merchant: String = "",
    val currency: Currency = Currency.INR,
    val error: String? = null
)

