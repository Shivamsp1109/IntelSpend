package com.spendwise.domain.usecase

import com.spendwise.domain.repository.ExpenseRepository
import javax.inject.Inject

class GetPagedExpensesUseCase @Inject constructor(
    private val repository: ExpenseRepository
) {
    operator fun invoke(filterState: com.spendwise.domain.model.ExpenseFilterState) = repository.observePagedExpenses(filterState)
}
