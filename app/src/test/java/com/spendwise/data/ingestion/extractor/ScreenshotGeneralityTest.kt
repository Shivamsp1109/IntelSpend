package com.spendwise.data.ingestion.extractor

import com.spendwise.data.ingestion.ocr.OcrLine
import com.spendwise.data.ingestion.ocr.OcrResult
import com.spendwise.domain.model.Currency
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Payment screens deliberately worded differently to the ones the extractor was fixed against.
 *
 * The point of this file is to catch overfitting. Every case here was written to defeat a
 * specific hardcoded list, and each one failed until the corresponding rule was rewritten
 * structurally — phrase lists for the user's share became "second person plus a payment word",
 * and a list of banner strings became status vocabulary.
 */
class ScreenshotGeneralityTest {
    private val extractor = ScreenshotExtractor()

    private fun extract(vararg lines: Triple<String, Int, Int>) =
        lines.map { (text, top, height) ->
            OcrLine(text = text, left = 100, top = top, right = 600, height = height)
        }.let { ocrLines ->
            extractor.extract(
                OcrResult(ocrLines.joinToString("\n") { it.text }, ocrLines)
            ).single()
        }

    private fun assertPicked(
        expectedMerchant: String,
        expectedAmount: Double,
        vararg lines: Triple<String, Int, Int>
    ) {
        val tx = extract(*lines)
        assertEquals(expectedMerchant, tx.merchant)
        assertEquals(expectedAmount, tx.amount, 0.0)
        assertEquals(Currency.INR, tx.currency)
    }

    /**
     * The user's share, phrased four different ways. None of these wordings is listed
     * anywhere — they are recognised by the line addressing the reader and naming a payment.
     */
    @Test
    fun `takes the users share whatever wording the app uses`() {
        assertPicked("The Table Co", 800.0,
            Triple("The Table Co", 100, 40),
            Triple("Payment done", 300, 26),
            Triple("₹2,400", 380, 70),
            Triple("Your share ₹800", 500, 26))

        assertPicked("Toit Brewpub", 1050.0,
            Triple("Toit Brewpub", 100, 40),
            Triple("Bill settled", 300, 26),
            Triple("₹3,200", 380, 70),
            Triple("You pay ₹1,050", 500, 26))

        assertPicked("Social Offline", 1333.0,
            Triple("Social Offline", 100, 40),
            Triple("₹4,000", 380, 70),
            Triple("Your portion: ₹1,333", 500, 26))

        assertPicked("Barbeque Nation", 1400.0,
            Triple("Barbeque Nation", 100, 40),
            Triple("₹5,600", 380, 70),
            Triple("You owe ₹1,400", 500, 26))
    }

    /** Status banners, worded differently to "Bill cleared" and placed either side of the name. */
    @Test
    fun `never mistakes a status banner for the merchant`() {
        assertPicked("Licious Meats", 1875.0,
            Triple("Licious Meats", 100, 40),
            Triple("Order delivered", 300, 26),
            Triple("₹1,875", 380, 70))

        assertPicked("Cult Fitness", 2499.0,
            Triple("Cult Fitness", 100, 40),
            Triple("Payment received", 300, 26),
            Triple("₹2,499", 380, 70))

        // Banner above the merchant, which defeats position-based ranking on its own.
        assertPicked("Reliance Digital", 12999.0,
            Triple("Money Sent", 90, 26),
            Triple("Reliance Digital", 160, 36),
            Triple("₹12,999", 250, 62),
            Triple("UPI Ref 998877665544", 340, 20))
    }

    @Test
    fun `handles the common single-payer app layouts`() {
        assertPicked("Blue Tokai Coffee", 450.0,
            Triple("Paid to", 100, 22),
            Triple("Blue Tokai Coffee", 130, 34),
            Triple("₹450", 260, 60),
            Triple("Completed", 340, 22))

        assertPicked("Zomato Online", 1240.0,
            Triple("Payment Successful", 90, 26),
            Triple("Zomato Online", 160, 36),
            Triple("₹1,240", 250, 62),
            Triple("Transaction ID T2401234", 340, 20))
    }

    /** Ordinary English words that contain currency codes as substrings. */
    @Test
    fun `does not misread currency from surrounding prose`() {
        assertPicked("Ironhill Bengaluru", 900.0,
            Triple("Ironhill Bengaluru", 100, 40),
            Triple("₹900", 300, 60),
            Triple("Friends receive a UPI link", 400, 22))

        assertPicked("Blinkit", 640.0,
            Triple("Blinkit", 100, 40),
            Triple("₹640", 300, 60),
            Triple("Delivered in 2 hours", 400, 22))
    }
}
