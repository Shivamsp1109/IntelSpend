package com.spendwise.data.ingestion.extractor

import com.google.gson.Gson
import com.spendwise.data.ingestion.ocr.OcrLine
import com.spendwise.data.ingestion.ocr.OcrResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

data class ExpectedTransaction(
    val title: String,
    val amount: Double,
    val currency: String,
    val merchant: String?,
    val type: String
)

data class OcrResultFixture(
    val fullText: String,
    val lines: List<OcrLineFixture>
)

data class OcrLineFixture(
    val text: String,
    val left: Int = 0,
    val top: Int,
    val right: Int = 0,
    val bottom: Int = 0,
    val height: Int
)

class ExtractionRegressionTest {

    @Test
    fun `run regression tests on ingestion fixtures`() {
        val rootDir = File("..").canonicalFile
        val testDataDir = File(rootDir, "test-data")
        val gson = Gson()
        
        val receiptsDir = File(testDataDir, "receipts")
        val screenshotsDir = File(testDataDir, "screenshots")

        var totalTests = 0
        var correctTests = 0

        // 1. Test receipts (using ReceiptExtractor)
        val receiptExpectedFiles = receiptsDir.listFiles { _, name -> name.endsWith(".expected.json") } ?: emptyArray()
        for (expectedFile in receiptExpectedFiles) {
            totalTests++
            val baseName = expectedFile.name.substringBefore(".expected.json")
            val ocrFile = File(receiptsDir, "$baseName.ocr.json")
            assert(ocrFile.exists()) { "Missing OCR fixture for ${expectedFile.name}" }

            val expected = gson.fromJson(expectedFile.readText(), ExpectedTransaction::class.java)
            val ocrFixture = gson.fromJson(ocrFile.readText(), OcrResultFixture::class.java)
            
            val ocrResult = OcrResult(
                fullText = ocrFixture.fullText,
                lines = ocrFixture.lines.map { 
                    OcrLine(
                        text = it.text,
                        left = it.left,
                        top = it.top,
                        right = it.right,
                        bottom = it.bottom,
                        height = it.height
                    )
                }
            )

            val extractor = ReceiptExtractor()
            val extractedList = extractor.extract(ocrResult)
            
            if (extractedList.isNotEmpty()) {
                val actual = extractedList.first()
                if (actual.amount == expected.amount && (expected.merchant == null || actual.merchant == expected.merchant)) {
                    correctTests++
                } else {
                    println("Receipt failure on $baseName: Expected amount=${expected.amount}, merchant=${expected.merchant}. Got amount=${actual.amount}, merchant=${actual.merchant}")
                }
            } else {
                println("Receipt failure on $baseName: No transaction extracted.")
            }
        }

        // 2. Test screenshots (using ScreenshotExtractor)
        val screenshotExpectedFiles = screenshotsDir.listFiles { _, name -> name.endsWith(".expected.json") } ?: emptyArray()
        for (expectedFile in screenshotExpectedFiles) {
            totalTests++
            val baseName = expectedFile.name.substringBefore(".expected.json")
            val ocrFile = File(screenshotsDir, "$baseName.ocr.json")
            assert(ocrFile.exists()) { "Missing OCR fixture for ${expectedFile.name}" }

            val expected = gson.fromJson(expectedFile.readText(), ExpectedTransaction::class.java)
            val ocrFixture = gson.fromJson(ocrFile.readText(), OcrResultFixture::class.java)
            
            val ocrResult = OcrResult(
                fullText = ocrFixture.fullText,
                lines = ocrFixture.lines.map { 
                    OcrLine(
                        text = it.text,
                        left = it.left,
                        top = it.top,
                        right = it.right,
                        bottom = it.bottom,
                        height = it.height
                    )
                }
            )

            val extractor = ScreenshotExtractor()
            val extractedList = extractor.extract(ocrResult)
            
            if (extractedList.isNotEmpty()) {
                val actual = extractedList.first()
                if (actual.amount == expected.amount && (expected.merchant == null || actual.merchant == expected.merchant)) {
                    correctTests++
                } else {
                    println("Screenshot failure on $baseName: Expected amount=${expected.amount}, merchant=${expected.merchant}. Got amount=${actual.amount}, merchant=${actual.merchant}")
                }
            } else {
                println("Screenshot failure on $baseName: No transaction extracted.")
            }
        }

        val accuracy = if (totalTests == 0) 0.0 else correctTests.toDouble() / totalTests
        println("Extraction accuracy: ${accuracy * 100}% ($correctTests/$totalTests)")
        assert(accuracy >= 0.90) { "Amount/Merchant extraction accuracy dropped below 90%" }
    }
}
