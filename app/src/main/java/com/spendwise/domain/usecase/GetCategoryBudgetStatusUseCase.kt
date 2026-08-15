package com.spendwise.domain.usecase

import com.spendwise.data.local.CategoryBudgetDao
import com.spendwise.data.local.CategorySpendRow
import com.spendwise.data.local.toDomain
import com.spendwise.domain.model.BudgetMonth
import com.spendwise.domain.model.CategoryBudget
import com.spendwise.domain.model.CategoryBudgetStatus
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Measures each category budget against what the current month has spent.
 *
 * Reactive, so adding a transaction moves the bar the user is looking at rather
 * than waiting for them to leave the screen and come back.
 */
class GetCategoryBudgetStatusUseCase @Inject constructor(
    private val budgetDao: CategoryBudgetDao
) {
    operator fun invoke(
        month: BudgetMonth = BudgetMonth.containing()
    ): Flow<List<CategoryBudgetStatus>> =
        combine(
            budgetDao.observeBudgets(),
            budgetDao.observeCategorySpend(month.start, month.endInclusive)
        ) { budgets, spend ->
            statusesFor(budgets.map { it.toDomain() }, spend)
        }

    companion object {
        /**
         * Pairs each budget with its spending.
         *
         * Pure and separate so the arithmetic can be tested without a database.
         *
         * Matched on category *and* currency. A rupee budget measured against
         * dollar spending would be nonsense in both directions — either wildly
         * over or permanently untouched — and the pairing is the only place that
         * can go wrong silently.
         *
         * A budget with no spending is kept, showing zero. It is the case the
         * user most wants confirmed, and dropping it would make the screen look
         * like the budget had been lost.
         */
        fun statusesFor(
            budgets: List<CategoryBudget>,
            spend: List<CategorySpendRow>
        ): List<CategoryBudgetStatus> {
            val spentBy = spend.associateBy { it.category to it.currency }

            return budgets
                .map { budget ->
                    CategoryBudgetStatus(
                        budget = budget,
                        spent = spentBy[budget.category.label to budget.currency.code]?.spent ?: 0.0
                    )
                }
                // Most urgent first: over budget, then closest to it. Someone
                // opening this screen is looking for what has gone wrong, and
                // alphabetical order buries it.
                .sortedByDescending { it.fractionUsed }
        }
    }
}
