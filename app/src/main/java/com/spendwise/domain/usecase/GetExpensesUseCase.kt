package com.spendwise.domain.usecase

import com.spendwise.domain.repository.ExpenseRepository
import javax.inject.Inject

class GetExpensesUseCase @Inject constructor(
    private val repository: ExpenseRepository
) {
    operator fun invoke() = repository.observeExpenses()
}
