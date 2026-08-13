package com.spendwise.data.ingestion.normalizer

import org.junit.Assert.assertEquals
import org.junit.Test

class MerchantNormalizerTest {

    /**
     * The reference-code stripper used to match any eight-plus character run case
     * insensitively, which silently deleted the name of every merchant that long.
     */
    @Test
    fun `keeps long merchant names while still stripping reference codes`() {
        assertEquals("Reliance Fresh", MerchantNormalizer.normalizeDescription("POS/RELIANCE FRESH/MUMBAI"))
        assertEquals("Starbucks Store", MerchantNormalizer.normalizeDescription("STARBUCKS STORE 411"))
        assertEquals("Flipkart Internet", MerchantNormalizer.normalizeDescription("UPI/FLIPKART INTERNET/order 4471829"))
        assertEquals("Decathlon Sports", MerchantNormalizer.normalizeDescription("POS/DECATHLON SPORTS/WHITEFIELD"))
    }

    @Test
    fun `strips alphanumeric transaction references`() {
        assertEquals("Amazon Pay", MerchantNormalizer.normalizeDescription("UPI/AMAZON PAY/AXISP0012345/order"))
        assertEquals("Zomato", MerchantNormalizer.normalizeDescription("UPI-ZOMATO-ZOMATO@paytm"))
    }

    /** Bank narrations read CHANNEL/MERCHANT/LOCATION — the city is not the merchant. */
    @Test
    fun `prefers the payee over the location`() {
        assertEquals("Swiggy", MerchantNormalizer.normalizeDescription("POS/SWIGGY/BANGALORE"))
        assertEquals("Big Bazaar", MerchantNormalizer.normalizeDescription("POS/BIG BAZAAR/HYDERABAD"))
    }

    /** Payee names are printed in caps; whatever the payer typed is lower case. */
    @Test
    fun `prefers the payee over a free-text note however long`() {
        assertEquals("Bescom", MerchantNormalizer.normalizeDescription("UPI/BESCOM/electricity bill"))
        assertEquals(
            "Globex Solutions",
            MerchantNormalizer.normalizeDescription("NEFT/GLOBEX SOLUTIONS/consulting invoice for December")
        )
    }

    @Test
    fun `drops corporate suffixes`() {
        assertEquals("Uber", MerchantNormalizer.normalize("UBER INDIA"))
        assertEquals("Globex", MerchantNormalizer.normalize("Globex Pvt Ltd"))
    }

    /**
     * An aggregator names itself first and the shop second. Displaying the
     * first word makes half a statement read "Paytm", which identifies nothing.
     */
    @Test
    fun `shows the shop rather than the payment processor`() {
        assertEquals("Swiggy", MerchantNormalizer.normalize("PAYTM*SWIGGY"))
        assertEquals("Blinkit", MerchantNormalizer.normalize("RAZORPAY BLINKIT"))
        assertEquals("Zomato", MerchantNormalizer.normalize("phonepe-ZOMATO"))
    }

    /**
     * When the processor is all the bank recorded, keep it. A blank name is
     * worse than a vague one.
     */
    @Test
    fun `keeps the processor when it is the only name present`() {
        assertEquals("Paytm", MerchantNormalizer.normalize("PAYTM"))
    }

    /** Type words that positional parsing missed, e.g. a narration ending in them. */
    @Test
    fun `trims transaction type words from the ends`() {
        assertEquals("Westside", MerchantNormalizer.normalize("WESTSIDE WDL TFR"))
        assertEquals("Flipkart", MerchantNormalizer.normalize("DEP TFR FLIPKART"))
    }

    /** A trailing branch address is the bank's, not part of the payee. */
    @Test
    fun `trims a trailing branch address`() {
        assertEquals("Westside", MerchantNormalizer.normalize("WESTSIDE CAMPUS BRANCH"))
    }

    /**
     * The counterpart, and the reason the two ends are not treated alike: these
     * words are only noise where the bank appends them. In the middle, or at the
     * start, they belong to the name.
     */
    @Test
    fun `leaves such words alone where they belong to the name`() {
        assertEquals("Metro Cash And Carry", MerchantNormalizer.normalize("METRO CASH AND CARRY"))
        assertEquals("Branch Cafe", MerchantNormalizer.normalize("BRANCH CAFE"))
    }
}
