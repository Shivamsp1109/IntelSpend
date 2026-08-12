package com.spendwise.data.ingestion.extractor

import com.spendwise.data.ingestion.model.TransactionType
import com.spendwise.data.ingestion.ocr.OcrLine
import com.spendwise.data.ingestion.ocr.OcrResult
import com.spendwise.domain.model.ExpenseSource
import org.junit.Assert.assertEquals
import org.junit.Test

class StatementExtractorTest {
    private val extractor = StatementExtractor()

    @Test
    fun `extracts debit amount instead of balance from statement row`() {
        val result = extractor.extract(
            OcrResult(
                fullText = """
                    Date Description Debit Credit Balance
                    02-Jul-2026 UPI-ZOMATO-ZOMATO@paytm 345.67 12,000.00
                """.trimIndent(),
                lines = listOf(
                    OcrLine("Date Description Debit Credit Balance", left = 0, top = 10, right = 500, height = 18),
                    OcrLine("02-Jul-2026 UPI-ZOMATO-ZOMATO@paytm 345.67 12,000.00", left = 0, top = 40, right = 620, height = 18)
                )
            )
        )

        assertEquals(1, result.size)
        val transaction = result.single()
        assertEquals("Zomato", transaction.merchant)
        assertEquals(345.67, transaction.amount, 0.0)
        assertEquals(TransactionType.DEBIT, transaction.type)
        assertEquals(ExpenseSource.PDF, transaction.source)
    }

    @Test
    fun `extracts multiple statement transactions independently`() {
        val result = extractor.extract(
            OcrResult(
                fullText = """
                    Date Description Debit Credit Balance
                    02-Jul-2026 UPI-ZOMATO-ZOMATO@paytm 345.67 12,000.00
                    03-Jul-2026 POS AMAZON 1,249.00 10,751.00
                    04-Jul-2026 REFUND AMAZON 249.00 11,000.00
                """.trimIndent(),
                lines = listOf(
                    OcrLine("Date Description Debit Credit Balance", left = 0, top = 10, right = 500, height = 18),
                    OcrLine("02-Jul-2026 UPI-ZOMATO-ZOMATO@paytm 345.67 12,000.00", left = 0, top = 40, right = 620, height = 18),
                    OcrLine("03-Jul-2026 POS AMAZON 1,249.00 10,751.00", left = 0, top = 70, right = 620, height = 18),
                    OcrLine("04-Jul-2026 REFUND AMAZON 249.00 11,000.00", left = 0, top = 100, right = 620, height = 18)
                )
            )
        )

        assertEquals(3, result.size)
        assertEquals("Zomato", result[0].merchant)
        assertEquals(345.67, result[0].amount, 0.0)
        assertEquals(TransactionType.DEBIT, result[0].type)

        assertEquals("Amazon", result[1].merchant)
        assertEquals(1249.0, result[1].amount, 0.0)
        assertEquals(TransactionType.DEBIT, result[1].type)

        assertEquals("Amazon", result[2].merchant)
        assertEquals(249.0, result[2].amount, 0.0)
        assertEquals(TransactionType.CREDIT, result[2].type)
    }
}
