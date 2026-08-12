package com.spendwise.data.ingestion.extractor

import com.spendwise.data.ingestion.ocr.OcrResult
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.ExpenseSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReceiptExtractorTest {
    private val extractor = ReceiptExtractor()

    @Test
    fun `extracts merchant total date and category from pharmacy gst bill`() {
        val text = """
            JANATHA BAZAAR DRUG COUNTER
            Unit of KSCCF Ltd, Nimhans Hospital Campus
            GSTIN: 29AAAAK0525H1ZW
            Patient : NAGENDRA PANDEY
            Bill No : S33161
            Date : 02-07-26
            QTY Item Name Hsn Code Mfg Batch Exp MRP Rate GST % Gst Amt Amount
            10 BACLOQUE 10 TAB 30049099 IPH TZOT-0866 12/27 12.00 9.22 05 4.60 92.15
            90 LONAZEP 0.25 MG TAB 30049082 SSS SIH0317A 01/28 2.15 1.83 05 8.24 164.79
            TAX TAXABLE CGST TAX AMT SGST TAX AMT
            Total Qty: 100
            Total Items: 2
            Tax Amt: 12.84
            Total: 270.00
        """.trimIndent()

        val result = extractor.extract(OcrResult(fullText = text, lines = emptyList()))

        assertEquals(1, result.size)
        val transaction = result.single()
        assertEquals("JANATHA BAZAAR DRUG COUNTER", transaction.merchant)
        assertEquals("JANATHA BAZAAR DRUG COUNTER", transaction.title)
        assertEquals(270.0, transaction.amount, 0.0)
        assertEquals(ExpenseCategory.Health, transaction.category)
        assertEquals(ExpenseSource.OCR, transaction.source)
        assertNotNull(transaction.date)
    }

    @Test
    fun `does not use note disclaimer as merchant when ocr order is messy`() {
        val text = """
            NOTE:
            1 E & O.E
            2 Subject to Bangalore Jurisdiction.
            TAX TAXABLE CGST TAX AMT SGST TAX AMT
            JANATHA BAZAAR DRUG COUNTER
            GST INVOICE
            Patient : NAGENDRA PANDEY
            Date : 02-07-26
            BACLOQUE 10 TAB 92.15
            LONAZEP 0.25 MG TAB 164.79
            Total: 270.00
        """.trimIndent()

        val result = extractor.extract(OcrResult(fullText = text, lines = emptyList()))

        assertEquals(1, result.size)
        val transaction = result.single()
        assertEquals("JANATHA BAZAAR DRUG COUNTER", transaction.merchant)
        assertEquals(270.0, transaction.amount, 0.0)
    }
}
