package com.spendwise.domain.usecase

import com.spendwise.domain.model.Income
import com.spendwise.domain.repository.IncomeRepository
import javax.inject.Inject

class DeleteIncomeUseCase @Inject constructor(
    private val repository: IncomeRepository
) {
    suspend operator fun invoke(income: Income) = repository.deleteIncome(income)
}
