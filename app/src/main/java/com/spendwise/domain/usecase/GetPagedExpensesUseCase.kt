package com.spendwise.domain.usecase

import com.spendwise.domain.repository.ExpenseRepository
import javax.inject.Inject

class GetPagedExpensesUseCase @Inject constructor(
    private val repository: ExpenseRepository
) {
    operator fun invoke(query: String, category: String?) =
        repository.observePagedExpenses(query, category)
}
