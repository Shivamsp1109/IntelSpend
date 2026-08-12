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
}
