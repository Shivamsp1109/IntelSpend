package com.spendwise.domain.usecase

import com.spendwise.domain.model.BudgetStatus
import com.spendwise.domain.model.Expense
import com.spendwise.util.DateUtils
import javax.inject.Inject

class GetBudgetStatusUseCase @Inject constructor() {
    operator fun invoke(expenses: List<Expense>, monthlyBudget: Double): BudgetStatus {
        val spent = expenses
            .filter { DateUtils.isThisMonth(it.date) }
            .sumOf { it.amount }
        return BudgetStatus(monthlyBudget, spent)
    }
}
