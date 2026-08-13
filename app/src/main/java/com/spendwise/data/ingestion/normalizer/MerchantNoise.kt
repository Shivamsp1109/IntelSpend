package com.spendwise.data.ingestion.normalizer

/**
 * Words that appear in bank narrations without saying anything about who was
 * paid.
 *
 * Shared between display normalisation and duplicate matching so the two cannot
 * drift apart — they were separate lists, and a word treated as noise by one and
 * as identity by the other produces a name on screen that does not match the
 * name the matcher compared.
 *
 * The lists are deliberately conservative. Every entry is a word that a real
 * trading name is very unlikely to contain, because the cost of being wrong is
 * asymmetric: stripping a word that was part of the name corrupts it, while
 * leaving one in merely looks untidy.
 *
 * Note what is *not* here. "India" and "Indian" read like boilerplate but are
 * part of Air India, Indian Oil and Indian Bank — stripping them collapsed
 * "Air India" and "Air Asia" onto the same single token, and the matcher then
 * called two different airlines the same payee.
 */
object MerchantNoise {

    /** Describes the transaction rather than the payee, at either end of a name. */
    val STRUCTURAL = setOf(
        "upi", "pos", "atm", "imps", "neft", "rtgs", "ach", "vpa",
        "dr", "cr", "tfr", "wdl", "dep", "chq", "txn", "ref", "na"
    )

    /**
     * Noise only where the bank appends it, not where a shop might start with it.
     *
     * "Branch" at the end of a narration is the branch address; at the start it
     * is a name — Branch Cafe, Branch Brewing. The asymmetry is the point, and
     * treating the two ends alike turned the second into "Cafe".
     */
    val TRAILING_ONLY = setOf("branch", "campus")

    /** Payment processors, which prefix the merchant they collected for. */
    val PROCESSORS = setOf(
        "paytm", "razorpay", "billdesk", "payu", "pinelabs", "ccavenue",
        "gpay", "phonepe", "bharatpe", "cred", "pytm"
    )

    /** Bank identifiers embedded in narration segments. */
    val BANK_CODES = setOf(
        "hdfc", "icici", "sbin", "utib", "yesb", "ratn", "kkbk", "idib"
    )

    /** Legal-form suffixes, which differ between how a shop trades and how it registers. */
    val CORPORATE = setOf(
        "ltd", "limited", "pvt", "private", "llp", "inc", "corp", "co"
    )

    /**
     * Everything that carries no identity, for comparing two names.
     *
     * Safe to be broader here than when cleaning a name for display, because
     * both sides are stripped the same way — removing a word from both cannot
     * make two different payees look alike, only two spellings of one payee.
     */
    val FOR_MATCHING: Set<String> = STRUCTURAL + PROCESSORS + BANK_CODES + CORPORATE +
        setOf("campus", "payment", "paid", "transaction", "transfer", "cash", "the")

    private val SEPARATOR = Regex("""[^A-Za-z0-9]+""")

    /**
     * Drops a leading processor, so a payment collected by an aggregator shows
     * the shop rather than the aggregator: "PAYTM*SWIGGY" reads as "SWIGGY".
     *
     * Only the leading position, and only when something remains — a narration
     * that names nothing but the processor is all the bank recorded, and an
     * empty name would be worse than a vague one.
     */
    fun stripProcessorPrefix(name: String): String {
        val parts = name.trim().split(SEPARATOR).filter { it.isNotEmpty() }
        if (parts.size < 2) return name

        val remaining = parts.dropWhile { it.lowercase() in PROCESSORS }
        return if (remaining.isEmpty()) name else remaining.joinToString(" ")
    }

    /**
     * Drops structural words from the ends of a name.
     *
     * Edges only. In the middle these words are far more likely to belong to the
     * name than to the narration — "Metro Cash & Carry" and "Branch Cafe" both
     * survive intact this way, and neither would if the same words were removed
     * wherever they appeared.
     */
    fun trimStructuralEdges(name: String): String {
        val parts = name.trim().split(" ").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return name

        val trimmed = parts
            .dropWhile { it.normalisedWord() in STRUCTURAL }
            .dropLastWhile { it.normalisedWord().let { w -> w in STRUCTURAL || w in TRAILING_ONLY } }

        return if (trimmed.isEmpty()) name else trimmed.joinToString(" ")
    }

    private fun String.normalisedWord(): String = trim('.', ',', '-', '/').lowercase()
}
