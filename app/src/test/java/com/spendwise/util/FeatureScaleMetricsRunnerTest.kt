package com.spendwise.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureScaleMetricsRunnerTest {
    @Test
    fun `measures realistic feature scale`() {
        val metrics = FeatureScaleMetricsRunner.measure()

        println(
            """
            SpendWise feature-scale metrics:
            expenses=${metrics.expenseCount}
            categories=${metrics.categoryCount}
            reportBytes=${metrics.reportBytes}
            generateExpensesMs=${metrics.generateExpensesMs}
            aggregateByCategoryMs=${metrics.aggregateByCategoryMs}
            generateReportPayloadMs=${metrics.generateReportPayloadMs}
            """.trimIndent()
        )

        assertEquals(10_000, metrics.expenseCount)
        assertEquals(500, metrics.categoryCount)
        assertTrue(metrics.reportBytes >= 50L * 1024L * 1024L)
    }
}
