package com.spendwise.data.ingestion.extractor

import com.spendwise.data.ingestion.model.TransactionType
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.ExpenseSource
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvExtractorTest {
    private val extractor = CsvExtractor()

    @Test
    fun `extracts debit credit merchant category and ignores balance column`() {
        val rows = listOf(
            listOf("Txn Date", "Narration", "Withdrawal", "Deposit", "Balance", "Category"),
            listOf("02-Jul-2026", "UPI-ZOMATO-ZOMATO@paytm-987654321", "345.67", "", "12,000.00", "Food"),
            listOf("03-Jul-2026", "SALARY CREDIT ACME PVT LTD", "", "50000.00", "62,000.00", "")
        )

        val result = extractor.extract(rows)

        assertEquals(2, result.size)
        assertEquals("Zomato", result[0].merchant)
        assertEquals(345.67, result[0].amount, 0.0)
        assertEquals(TransactionType.DEBIT, result[0].type)
        assertEquals(ExpenseCategory.FoodDining, result[0].category)
        assertEquals(ExpenseSource.CSV, result[0].source)

        assertEquals("Salary Acme", result[1].merchant)
        assertEquals(50000.0, result[1].amount, 0.0)
        assertEquals(TransactionType.CREDIT, result[1].type)
    }

    @Test
    fun `extracts signed amount column without dropping debit rows`() {
        val rows = listOf(
            listOf("Date", "Description", "Amount", "Currency"),
            listOf("2026-07-04", "POS AMAZON 123456", "-1,249.00", "INR"),
            listOf("2026-07-05", "Refund AMAZON", "+249.00", "INR")
        )

        val result = extractor.extract(rows)

        assertEquals(2, result.size)
        assertEquals("Amazon", result[0].merchant)
        assertEquals(1249.0, result[0].amount, 0.0)
        assertEquals(TransactionType.DEBIT, result[0].type)
        assertEquals("Amazon", result[1].merchant)
        assertEquals(249.0, result[1].amount, 0.0)
        assertEquals(TransactionType.CREDIT, result[1].type)
    }
}
