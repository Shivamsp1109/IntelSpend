package com.spendwise.data.ingestion.extractor

import com.spendwise.data.ingestion.ocr.OcrLine
import com.spendwise.data.ingestion.ocr.OcrResult
import com.spendwise.domain.model.ExpenseSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotExtractorTest {
    private val extractor = ScreenshotExtractor()

    @Test
    fun `extracts largest amount and merchant above amount from payment screenshot`() {
        val result = extractor.extract(
            OcrResult(
                fullText = """
                    Axis Bank Credit Card
                    Paid successfully
                    ₹795
                    Transaction ID 12345
                """.trimIndent(),
                lines = listOf(
                    OcrLine("Axis Bank Credit Card", top = 100, height = 42),
                    OcrLine("Paid successfully", top = 165, height = 20),
                    OcrLine("₹795", top = 230, height = 64),
                    OcrLine("Transaction ID 12345", top = 320, height = 18)
                )
            )
        )

        assertEquals(1, result.size)
        val transaction = result.single()
        assertEquals("Axis Bank Credit Card", transaction.merchant)
        assertEquals("Axis Bank Credit Card", transaction.title)
        assertEquals(795.0, transaction.amount, 0.0)
        assertEquals(ExpenseSource.SCREENSHOT, transaction.source)
    }

    @Test
    fun `uses paid to label and ignores transaction id as amount`() {
        val result = extractor.extract(
            OcrResult(
                fullText = """
                    Payment Successful
                    Paid to
                    Swiggy
                    ₹432.50
                    UPI Transaction ID 987654321
                """.trimIndent(),
                lines = listOf(
                    OcrLine("Payment Successful", top = 80, height = 24),
                    OcrLine("Paid to", top = 125, height = 18),
                    OcrLine("Swiggy", top = 150, height = 28),
                    OcrLine("₹432.50", top = 210, height = 54),
                    OcrLine("UPI Transaction ID 987654321", top = 300, height = 18)
                )
            )
        )

        assertEquals(1, result.size)
        val transaction = result.single()
        assertEquals("Swiggy", transaction.merchant)
        assertEquals("Swiggy", transaction.title)
        assertEquals(432.50, transaction.amount, 0.0)
    }

    /**
     * Split-bill receipt: the table total is the largest text on screen, but the user's own
     * share is the expense, and the merchant sits above a "Bill cleared" status banner.
     */
    @Test
    fun `takes the users share and the merchant above the status banner`() {
        val result = extractor.extract(
            OcrResult(
                fullText = """
                    Ironhill Bengaluru
                    Marathahalli, Bangalore
                    Bill cleared
                    ₹15,635
                    ₹14,685 paid by you
                """.trimIndent(),
                lines = listOf(
                    OcrLine("Ironhill Bengaluru", left = 178, top = 275, right = 560, height = 40),
                    OcrLine("Marathahalli, Bangalore", left = 218, top = 328, right = 520, height = 24),
                    OcrLine("Bill cleared", left = 288, top = 512, right = 450, height = 28),
                    OcrLine("₹15,635", left = 250, top = 575, right = 490, height = 70),
                    OcrLine("₹14,685 paid by you", left = 245, top = 722, right = 495, height = 28)
                )
            )
        )

        val transaction = result.single()
        assertEquals("Ironhill Bengaluru", transaction.merchant)
        assertEquals(14685.0, transaction.amount, 0.0)
    }
}
