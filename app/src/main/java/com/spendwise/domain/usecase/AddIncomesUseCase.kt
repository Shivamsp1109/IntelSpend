package com.spendwise.domain.usecase

import com.spendwise.domain.model.Income
import com.spendwise.domain.repository.IncomeRepository
import javax.inject.Inject

class AddIncomesUseCase @Inject constructor(
    private val repository: IncomeRepository
) {
    suspend operator fun invoke(incomes: List<Income>) {
        repository.addIncomesBatch(incomes)
    }
}
