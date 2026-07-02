package com.spendwise.domain.usecase

import com.spendwise.domain.repository.IncomeRepository
import javax.inject.Inject

class GetIncomesUseCase @Inject constructor(
    private val repository: IncomeRepository
) {
    operator fun invoke() = repository.observeIncomes()
}
