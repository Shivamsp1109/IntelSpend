package com.spendwise.data.ingestion.duplicate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deciding whether two payee names mean the same shop.
 *
 * These pairs are the real reason cross-source duplicates were being missed. A
 * payment app shows the payee as the user knows them; the bank writes whatever
 * the acquirer registered, wrapped in processor and branch boilerplate. The
 * previous rule allowed three edits between the raw strings, which none of the
 * first group are within.
 */
class MerchantSimilarityTest {

    @Test
    fun `a bank's rendering matches the app's`() {
        assertTrue(MerchantSimilarity.sameMerchant("Swiggy", "SWIGGY LIMITED"))
        assertTrue(MerchantSimilarity.sameMerchant("Swiggy", "PAYTM*SWIGGY"))
        assertTrue(MerchantSimilarity.sameMerchant("Swiggy", "Swiggy Ltd Bangalore"))
        assertTrue(MerchantSimilarity.sameMerchant("Amazon", "AMAZON PAY INDIA PVT LTD"))
        assertTrue(MerchantSimilarity.sameMerchant("IRCTC", "irctc ltd"))
    }

    /** Banks truncate narration fields to a fixed width. */
    @Test
    fun `a truncated name matches the full one`() {
        assertTrue(MerchantSimilarity.sameMerchant("Flipkart", "Flipkar"))
        assertTrue(MerchantSimilarity.sameMerchant("Westside", "WESTSID"))
    }

    /**
     * The counterpart. An absolute three-edit threshold called these the same
     * merchant, because short names are close together by construction.
     */
    @Test
    fun `short names that merely resemble each other stay distinct`() {
        assertFalse(MerchantSimilarity.sameMerchant("Ola", "Oyo"))
        assertFalse(MerchantSimilarity.sameMerchant("Zomato", "Zepto"))
        assertFalse(MerchantSimilarity.sameMerchant("BSNL", "BSES"))
    }

    /**
     * Sharing a first word is not sharing an identity. Two airlines, two banks
     * and two hotels of the same chain-prefix are different payees, and calling
     * them the same would let one auto-deselect the other.
     */
    @Test
    fun `names that share only their first word stay distinct`() {
        assertFalse(MerchantSimilarity.sameMerchant("Air India", "Air Asia"))
        assertFalse(MerchantSimilarity.sameMerchant("Hotel Sunrise", "Hotel Paradise"))
        assertFalse(MerchantSimilarity.sameMerchant("Apollo Pharmacy", "Apollo Hospital"))
    }

    @Test
    fun `unrelated payees do not match`() {
        assertFalse(MerchantSimilarity.sameMerchant("Swiggy", "Blinkit"))
        assertFalse(MerchantSimilarity.sameMerchant("Reliance Fresh", "Metro Card Recharge"))
    }

    /**
     * Two payments through the same processor to different shops share the word
     * "paytm" and nothing else. Without stripping processor names they would
     * look related.
     */
    @Test
    fun `sharing only a processor name is not sharing a payee`() {
        assertFalse(MerchantSimilarity.sameMerchant("PAYTM*SWIGGY", "PAYTM*ZOMATO"))
        assertFalse(MerchantSimilarity.sameMerchant("RAZORPAY BLINKIT", "RAZORPAY UBER"))
    }

    /**
     * Bank narrations carry branch boilerplate. Two withdrawals from the same
     * branch are not two payments to the same payee.
     */
    @Test
    fun `sharing only branch boilerplate is not sharing a payee`() {
        assertFalse(
            MerchantSimilarity.sameMerchant(
                "Axis Vet. College Campus Branch Wdl Tfr",
                "Westside Vet. College Campus Branch Wdl Tfr"
            )
        )
    }

    /**
     * An unknown name is unknown, not a wildcard. The old check treated a null
     * on either side as an automatic match, so anything without a payee
     * collided with the first same-amount row it met.
     */
    @Test
    fun `a missing name never matches`() {
        assertFalse(MerchantSimilarity.sameMerchant(null, "Swiggy"))
        assertFalse(MerchantSimilarity.sameMerchant("Swiggy", null))
        assertFalse(MerchantSimilarity.sameMerchant(null, null))
        assertFalse(MerchantSimilarity.sameMerchant("", "Swiggy"))
    }

    @Test
    fun `case and punctuation are ignored`() {
        assertTrue(MerchantSimilarity.sameMerchant("reliance fresh", "RELIANCE-FRESH"))
        assertTrue(MerchantSimilarity.sameMerchant("Third Wave Coffee", "THIRD_WAVE_COFFEE"))
    }
}
