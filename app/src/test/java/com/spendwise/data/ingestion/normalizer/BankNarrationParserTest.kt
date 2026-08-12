package com.spendwise.data.ingestion.normalizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Narrations taken verbatim from a real Bihar-region statement PDF.
 *
 * Before positional parsing, every one of these produced "Wdl Tfr" or "Dep Tfr":
 * the transaction-type prefix sits at segment 0 so it takes no position penalty,
 * and it is all-caps so it takes the proper-noun bonus. It outscored the payee
 * on every row in the statement.
 */
class BankNarrationParserTest {

    private fun narration(body: String) =
        "$body 0097696162090 AT 09006 Bihar Vet. College Campus Branch"

    @Test
    fun `reads the payee from UPI debit narrations`() {
        assertEquals("Westside", MerchantNormalizer.normalizeDescription(
            narration("WDL TFR UPI/DR/621427195933/WESTSIDE /HDFC/westside.4/UPI")))
        assertEquals("Nexus Da", MerchantNormalizer.normalizeDescription(
            narration("WDL TFR UPI/DR/821498786448/Nexus Da/YESB/q281339603/UPI")))
        assertEquals("Kusum P", MerchantNormalizer.normalizeDescription(
            narration("WDL TFR UPI/DR/110352967844/KUSUM P/SBIN/arya.kumar/na")))
        assertEquals("Amazon I", MerchantNormalizer.normalizeDescription(
            narration("WDL TFR UPI/DR/621536166049/Amazon I/RATN/amazon@rap/Amaz")))
    }

    @Test
    fun `reads the payee from UPI credit narrations`() {
        assertEquals("Flipkart", MerchantNormalizer.normalizeDescription(
            narration("DEP TFR UPI/CR/621705377726/Flipkart/YESB/paytm-5650/expr")))
    }

    @Test
    fun `strips the IMPS reference that precedes the payee`() {
        assertEquals("Rayole S", MerchantNormalizer.normalizeDescription(
            narration("DEP TFR IMPS/621554709153/RE1-XX288-RAYOLE S/Salary")))
    }

    /** The branch address is longer than the payee and would otherwise win. */
    @Test
    fun `ignores the trailing branch and account boilerplate`() {
        val merchant = MerchantNormalizer.normalizeDescription(
            narration("WDL TFR UPI/DR/638741222299/IRCTC Ti/UTIB/pinelabs.1/Paym"))
        assertEquals("Irctc Ti", merchant)
    }

    @Test
    fun `returns null for narrations that are not a known bank layout`() {
        assertNull(BankNarrationParser.merchantFrom("Grocery shopping at the corner store"))
        assertNull(BankNarrationParser.merchantFrom(""))
    }

    /** Free-text and POS narrations must still reach the existing heuristics. */
    @Test
    fun `leaves non-bank descriptions to the heuristic path`() {
        assertEquals("Swiggy", MerchantNormalizer.normalizeDescription("POS/SWIGGY/BANGALORE"))
        assertEquals("Starbucks Store", MerchantNormalizer.normalizeDescription("STARBUCKS STORE 411"))
    }
}
