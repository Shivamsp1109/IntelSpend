package com.spendwise.data.ingestion.duplicate

import com.spendwise.data.ingestion.normalizer.MerchantNoise
import kotlin.math.max

/**
 * Decides whether two merchant names refer to the same payee.
 *
 * The hard case is comparing across sources. A UPI app shows the payee as the
 * user knows them — `Swiggy`. The bank statement for the same payment writes it
 * however the acquirer registered it: `SWIGGY LIMITED`, `PAYTM*SWIGGY`,
 * `Swiggy Ltd Bangalore`, or truncated to `Swigg`. Comparing those raw fails on
 * every one, which is exactly why the previous edit-distance check let
 * cross-source duplicates through.
 *
 * So the names are stripped down to the words that carry identity, and compared
 * on those. `swiggy limited` and `paytm*swiggy` both reduce to the token
 * `swiggy`, and match.
 */
object MerchantSimilarity {

    /**
     * Words that appear in bank narrations regardless of who was paid, and so
     * carry no information about identity. Left in, `PAYTM SWIGGY` and
     * `PAYTM ZOMATO` would look similar because they share a token.
     *
     * Shared with display normalisation — see MerchantNoise, which also explains
     * why "India" is deliberately absent.
     */
    private val NOISE_TOKENS = MerchantNoise.FOR_MATCHING

    /**
     * Below this the names are treated as different payees.
     *
     * Set above where "Air India" and "Air Asia" land. Those differ by three
     * edits across eight characters, which a looser threshold accepted — and a
     * wrong match here is not cosmetic, it reports LIKELY and takes a real
     * transaction out of the import.
     */
    private const val MATCH_THRESHOLD = 0.75

    /**
     * How much of the shorter name the shared tokens must account for.
     *
     * Above one-half on purpose: sharing a single word out of two is what
     * "Hotel Sunrise" and "Hotel Paradise" have in common, and that is not an
     * identity.
     */
    private const val LEADING_OVERLAP = 0.6

    /**
     * True when both names plausibly identify the same payee.
     *
     * Null on either side means unknown, not "matches anything" — the caller
     * decides what to do with an unverifiable merchant rather than having this
     * quietly wave it through.
     */
    fun sameMerchant(first: String?, second: String?): Boolean {
        if (first.isNullOrBlank() || second.isNullOrBlank()) return false
        return similarity(first, second) >= MATCH_THRESHOLD
    }

    /** 0.0 (nothing in common) to 1.0 (identical after normalisation). */
    fun similarity(first: String, second: String): Double {
        val left = tokens(first)
        val right = tokens(second)
        if (left.isEmpty() || right.isEmpty()) return 0.0

        val shared = left.intersect(right.toSet())
        if (shared.isNotEmpty()) {
            // A shared token is only decisive when it is the *leading* one.
            // Bank narrations put the payee first and address boilerplate after,
            // so "Axis Vet College Campus Branch" and "Westside Vet College
            // Campus Branch" share two tokens while naming different payees —
            // treating any overlap as a match merged exactly those.
            val shorter = if (left.size <= right.size) left else right
            val overlap = shared.size.toDouble() / minOf(left.size, right.size)
            if (shorter.first() in shared && overlap >= LEADING_OVERLAP) return 1.0
            // Otherwise fall through: the shared words may still be incidental.
        }

        // Comparing the joined text catches truncation ("swigg" against
        // "swiggy") and small misreadings.
        val leftText = left.joinToString("")
        val rightText = right.joinToString("")

        if (leftText.startsWith(rightText) || rightText.startsWith(leftText)) {
            // Guard against a two-character prefix matching half the alphabet.
            val shorter = minOf(leftText.length, rightText.length)
            return if (shorter >= 4) 1.0 else 0.0
        }

        return ratio(leftText, rightText)
    }

    /**
     * Lower-cases, splits on anything that is not a letter or digit, and drops
     * noise words and one-character fragments.
     *
     * Order is preserved, because position carries meaning: both payment apps
     * and bank narrations lead with the payee.
     */
    private fun tokens(name: String): List<String> =
        name.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 && it !in NOISE_TOKENS && !it.all { c -> c.isDigit() } }
            .distinct()

    /**
     * Edit distance as a proportion of the longer string.
     *
     * Relative rather than absolute, because an absolute threshold behaves
     * differently at different lengths: three edits is a rounding error across
     * twenty characters but the whole of a three-letter name. The old check
     * allowed three edits regardless, which meant `Ola` and `Oyo` — two edits
     * apart — counted as the same merchant.
     */
    private fun ratio(first: String, second: String): Double {
        val longest = max(first.length, second.length)
        if (longest == 0) return 1.0
        return 1.0 - levenshtein(first, second).toDouble() / longest
    }

    private fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
        var cost = IntArray(lhs.length + 1) { it }
        var newCost = IntArray(lhs.length + 1)

        for (i in 1..rhs.length) {
            newCost[0] = i
            for (j in 1..lhs.length) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                newCost[j] = minOf(
                    cost[j] + 1,
                    newCost[j - 1] + 1,
                    cost[j - 1] + match
                )
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhs.length]
    }
}
