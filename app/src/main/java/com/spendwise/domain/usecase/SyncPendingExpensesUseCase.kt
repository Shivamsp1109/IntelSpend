package com.spendwise.domain.usecase

import com.spendwise.domain.repository.ExpenseRepository
import javax.inject.Inject

class SyncPendingExpensesUseCase @Inject constructor(
    private val repository: ExpenseRepository
) {
    suspend operator fun invoke() = repository.syncPendingExpenses()
}
