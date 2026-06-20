package com.spendwise.util

import kotlin.random.Random
import kotlin.system.measureTimeMillis

data class FeatureScaleMetrics(
    val expenseCount: Int,
    val categoryCount: Int,
    val reportBytes: Long,
    val generateExpensesMs: Long,
    val aggregateByCategoryMs: Long,
    val generateReportPayloadMs: Long
)

object FeatureScaleMetricsRunner {
    fun measure(
        expenseCount: Int = 10_000,
        categoryCount: Int = 500,
        reportBytesTarget: Long = 50L * 1024L * 1024L
    ): FeatureScaleMetrics {
        lateinit var expenses: List<ScaleExpense>
        val generateExpensesMs = measureTimeMillis {
            expenses = generateExpenses(expenseCount, categoryCount)
        }

        val aggregateByCategoryMs = measureTimeMillis {
            expenses.groupBy { it.category }
                .mapValues { entry -> entry.value.sumOf { it.amount } }
        }

        var reportBytes = 0L
        val generateReportPayloadMs = measureTimeMillis {
            reportBytes = generateReportPayload(expenses, reportBytesTarget).length.toLong()
        }

        return FeatureScaleMetrics(
            expenseCount = expenses.size,
            categoryCount = categoryCount,
            reportBytes = reportBytes,
            generateExpensesMs = generateExpensesMs,
            aggregateByCategoryMs = aggregateByCategoryMs,
            generateReportPayloadMs = generateReportPayloadMs
        )
    }

    private fun generateExpenses(count: Int, categoryCount: Int): List<ScaleExpense> {
        val random = Random(7)
        val categories = List(categoryCount) { "Category ${it + 1}" }
        return List(count) { index ->
            ScaleExpense(
                id = index + 1,
                title = "Expense ${index + 1}",
                amount = random.nextDouble(20.0, 5_000.0),
                category = categories[index % categories.size],
                date = System.currentTimeMillis() - (index * 86_400_000L / categoryCount.coerceAtLeast(1))
            )
        }
    }

    private fun generateReportPayload(expenses: List<ScaleExpense>, targetBytes: Long): String {
        val builder = StringBuilder(targetBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        builder.appendLine("id,title,amount,category,date")
        var index = 0
        while (builder.length < targetBytes) {
            val expense = expenses[index % expenses.size]
            builder
                .append(expense.id)
                .append(',')
                .append(expense.title)
                .append(',')
                .append(expense.amount)
                .append(',')
                .append(expense.category)
                .append(',')
                .append(expense.date)
                .append('\n')
            index++
        }
        return builder.toString()
    }

    private data class ScaleExpense(
        val id: Int,
        val title: String,
        val amount: Double,
        val category: String,
        val date: Long
    )
}
