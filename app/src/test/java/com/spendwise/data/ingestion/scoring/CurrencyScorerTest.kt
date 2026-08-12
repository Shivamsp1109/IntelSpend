package com.spendwise.data.ingestion.scoring

import com.spendwise.domain.model.Currency
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyScorerTest {

    private fun best(text: String): Currency =
        CurrencyScorer.scoreCandidates(text).maxByOrNull { it.score }!!.value

    /**
     * Currency codes are matched as whole words. Matched as substrings, "FR" fires inside
     * "Friends" and labels an Indian restaurant bill in Swiss francs.
     */
    @Test
    fun `does not read currency codes out of ordinary words`() {
        assertEquals(Currency.INR, best("Split this bill. Friends receive a UPI link on WhatsApp"))
        assertEquals(Currency.INR, best("Delivered in 2 hours"))
        assertEquals(Currency.INR, best("Card used at Ironhill Bengaluru"))
    }

    @Test
    fun `still recognises genuine codes and symbols`() {
        assertEquals(Currency.CHF, best("Total CHF 240.00"))
        assertEquals(Currency.INR, best("₹15,635"))
        assertEquals(Currency.INR, best("Rs. 1,299"))
        assertEquals(Currency.USD, best("USD 42.99"))
        assertEquals(Currency.EUR, best("€1.299,00"))
    }

    /** With no currency evidence at all, fall back rather than inventing one. */
    @Test
    fun `falls back to the default when nothing matches`() {
        assertEquals(Currency.INR, best("Bill cleared"))
    }
}
