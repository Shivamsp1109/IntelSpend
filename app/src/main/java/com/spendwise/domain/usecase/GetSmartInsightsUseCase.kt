package com.spendwise.domain.usecase

import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.Insight
import com.spendwise.util.DateUtils
import javax.inject.Inject

class GetSmartInsightsUseCase @Inject constructor() {
    operator fun invoke(expenses: List<Expense>): List<Insight> {
        val current = expenses.filter { DateUtils.isThisMonth(it.date) }
            .groupBy { it.category }
            .mapValues { entry -> entry.value.sumOf { it.amount } }
        val previous = expenses.filter { DateUtils.isPreviousMonth(it.date) }
            .groupBy { it.category }
            .mapValues { entry -> entry.value.sumOf { it.amount } }

        return current.mapNotNull { (category, currentTotal) ->
            val previousTotal = previous[category] ?: return@mapNotNull null
            if (previousTotal <= 0.0) return@mapNotNull null
            val change = ((currentTotal - previousTotal) / previousTotal) * 100
            if (kotlin.math.abs(change) < 10) return@mapNotNull null
            val direction = if (change > 0) "increased" else "decreased"
            Insight(
                title = "${category.label} spending $direction",
                description = "${category.label} spending $direction by ${kotlin.math.abs(change).toInt()}% versus last month."
            )
        }
    }
}
