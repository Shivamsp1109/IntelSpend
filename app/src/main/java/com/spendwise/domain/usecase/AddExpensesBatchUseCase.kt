package com.spendwise.domain.usecase

import com.spendwise.domain.model.Expense
import com.spendwise.domain.repository.ExpenseRepository
import javax.inject.Inject

class AddExpensesBatchUseCase @Inject constructor(
    private val repository: ExpenseRepository
) {
    suspend operator fun invoke(expenses: List<Expense>) {
        repository.addExpensesBatch(expenses)
    }
}
