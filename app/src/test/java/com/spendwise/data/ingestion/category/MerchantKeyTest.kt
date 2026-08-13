package com.spendwise.data.ingestion.category

import com.spendwise.data.ingestion.normalizer.MerchantNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The key the learned-category cache is stored under.
 *
 * The cache is what stops a merchant being sent to the model twice: an answer is
 * written under the normalised merchant name and looked up the same way. That
 * only holds if normalisation is idempotent, because the write path and the read
 * path do not apply it the same number of times — CategoryPredictor normalises
 * before calling the store, which normalises again.
 *
 * If that ever stopped being true the cache would miss silently: no error, no
 * wrong answer, just the same merchant classified and paid for on every single
 * import. Worth pinning precisely because nothing would look broken.
 */
class MerchantKeyTest {

    private val samples = listOf(
        "Swiggy",
        "SWIGGY LIMITED",
        "PAYTM*SWIGGY",
        "RAZORPAY BLINKIT",
        "Uber India",
        "Globex Pvt Ltd",
        "WESTSIDE CAMPUS BRANCH",
        "WESTSIDE WDL TFR",
        "reliance fresh",
        "RELIANCE-FRESH",
        "Third Wave Coffee",
        "Metro Cash And Carry",
        "Amazon Pay India Pvt Ltd",
        "IRCTC"
    )

    /** Normalising an already-normalised name must not change it again. */
    @Test
    fun `normalisation is idempotent`() {
        for (raw in samples) {
            val once = MerchantNormalizer.normalize(raw)
            val twice = MerchantNormalizer.normalize(once)

            assertEquals("normalising '$raw' twice must equal normalising it once", once, twice)
        }
    }

    /** Three passes, since the two paths differ by more than one application. */
    @Test
    fun `normalisation is stable under repeated application`() {
        for (raw in samples) {
            val once = MerchantNormalizer.normalize(raw)
            val thrice = MerchantNormalizer.normalize(
                MerchantNormalizer.normalize(MerchantNormalizer.normalize(raw))
            )

            assertEquals(once, thrice)
        }
    }

    /**
     * The property that actually matters: however the same shop is written on
     * two different statements, it resolves to one cache key — so it is asked
     * about once and remembered thereafter.
     */
    @Test
    fun `variants of one merchant collapse to one key`() {
        val swiggy = listOf("Swiggy", "SWIGGY", "swiggy", "PAYTM*SWIGGY", "SWIGGY LIMITED")
            .map { MerchantNormalizer.normalize(it) }
            .distinct()

        assertEquals("all spellings of Swiggy must share a cache key: $swiggy", 1, swiggy.size)
    }

    /** And the counterpart — two different shops must not share one key. */
    @Test
    fun `different merchants keep different keys`() {
        val keys = listOf("Swiggy", "Zomato", "Blinkit", "Zepto")
            .map { MerchantNormalizer.normalize(it) }

        assertEquals(keys.size, keys.distinct().size)
    }
}
