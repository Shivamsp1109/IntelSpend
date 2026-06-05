package com.spendwise.domain.usecase

import com.spendwise.domain.model.Expense
import com.spendwise.domain.repository.ExpenseRepository
import javax.inject.Inject

class DeleteExpenseUseCase @Inject constructor(
    private val repository: ExpenseRepository
) {
    suspend operator fun invoke(expense: Expense) = repository.deleteExpense(expense)
}
