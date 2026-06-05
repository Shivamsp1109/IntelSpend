package com.spendwise.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.usecase.GetExpensesUseCase
import com.spendwise.util.DateUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    getExpensesUseCase: GetExpensesUseCase
) : ViewModel() {
    val uiState: StateFlow<AnalyticsUiState> = getExpensesUseCase()
        .map { expenses ->
            AnalyticsUiState(
                monthlySpending = expenses.groupBy { DateUtils.monthKey(it.date) }
                    .mapValues { entry -> entry.value.sumOf { it.amount } }
                    .toList()
                    .takeLast(6),
                categoryDistribution = expenses.groupBy { it.category }
                    .mapValues { entry -> entry.value.sumOf { it.amount } },
                weeklyTrend = expenses.groupBy { DateUtils.dayOfWeek(it.date) }
                    .mapValues { entry -> entry.value.sumOf { it.amount } }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState())
}

data class AnalyticsUiState(
    val monthlySpending: List<Pair<String, Double>> = emptyList(),
    val categoryDistribution: Map<ExpenseCategory, Double> = emptyMap(),
    val weeklyTrend: Map<String, Double> = emptyMap()
)
