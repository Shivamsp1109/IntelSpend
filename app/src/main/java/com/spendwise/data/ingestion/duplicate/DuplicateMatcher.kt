package com.spendwise.data.ingestion.duplicate

import com.spendwise.data.ingestion.model.DuplicateConfidence
import com.spendwise.data.ingestion.model.RawTransaction
import com.spendwise.data.ingestion.model.TransactionType
import com.spendwise.util.DateUtils
import kotlin.math.abs

/** A transaction already on record, reduced to the fields matching cares about. */
data class ExistingTransaction(
    val amount: Double,
    val date: Long,
    val merchant: String?,
    val currency: String,
    val reference: String?,
    val title: String,
    /** True when this row's date was substituted at import rather than read. */
    val dateIsAssumed: Boolean = false
)

/**
 * Decides which incoming transactions are already recorded.
 *
 * The case this exists for: someone screenshots a ₹500 UPI payment on the 13th
 * and imports it, then imports their bank statement on the 19th. The statement
 * lists that same payment. Almost nothing about the two records matches — the
 * screenshot says `Swiggy`, the statement says `UPI/DR/.../SWIGGY LIMITED`, and
 * the posting timestamp differs — so a naive comparison imports it twice and
 * overstates the month's spending.
 *
 * Matching runs in tiers, strongest evidence first:
 *
 *  1. **The reference number.** Both documents quote the same RRN or UTR. That
 *     is identity, not resemblance, and needs no other agreement.
 *  2. **Amount, currency, direction, date, and a recognisable merchant.**
 *  3. **The same, without merchant agreement.** Reported, never acted on.
 *
 * The tiers matter because the two ends deserve different treatment. Wrongly
 * merging two genuinely separate payments removes one from the user's records
 * with nothing to alert them, whereas a duplicate that slips through is visible
 * and fixable. So only the top two tiers deselect anything; the weakest tier is
 * surfaced as a question and left ticked.
 *
 * Kept free of database access so the rules can be exercised directly — they
 * are the part worth testing, and the queries around them are two range reads.
 */
object DuplicateMatcher {

    /**
     * How far apart two records of one payment can sit.
     *
     * Wider than seems necessary because banks post on business days: a Friday
     * evening payment can land on the statement the following Monday, and a
     * public holiday stretches that further. Too narrow and the cross-source
     * case this exists for slips straight through.
     */
    const val DATE_WINDOW_MILLIS = 4L * 24 * 60 * 60 * 1000

    /** Amounts are currency values; below a paisa is the same figure. */
    private const val AMOUNT_TOLERANCE = 0.01

    fun flag(
        transactions: List<RawTransaction>,
        existingExpenses: List<ExistingTransaction>,
        existingIncomes: List<ExistingTransaction>
    ) {
        // Everything accepted so far in this same batch, so a statement listing
        // a row twice — or two pages that overlap — is caught as well. Checking
        // only the database would miss that entirely.
        val acceptedExpenses = mutableListOf<ExistingTransaction>()
        val acceptedIncomes = mutableListOf<ExistingTransaction>()

        for (tx in transactions) {
            val isDebit = tx.type == TransactionType.DEBIT
            val onRecord = if (isDebit) existingExpenses else existingIncomes
            val earlierInBatch = if (isDebit) acceptedExpenses else acceptedIncomes

            // Two rows inside one document are held to a stricter standard than
            // a row measured against what is already stored.
            //
            // A statement that lists two ₹120 payments to the same coffee shop
            // on the same day is telling us there were two — buying coffee
            // twice is ordinary, and a document repeating a row is not. So
            // within a batch only a matching reference counts, while against
            // stored data a familiar payee and amount is enough, because that
            // is the shape a re-import or a cross-source duplicate takes.
            // Both pools are consulted and the stronger answer wins. Taking the
            // first non-null instead lets a weak stored match settle the
            // question: an unrelated payment of the same amount would report
            // "possible", which deselects nothing, and a genuine repeat sitting
            // in the same batch would never be looked for.
            val match = listOfNotNull(
                bestMatch(tx, onRecord),
                bestMatch(tx, earlierInBatch)?.takeIf { it.confidence == DuplicateConfidence.CERTAIN }
            ).maxByOrNull { it.confidence }

            tx.duplicateConfidence = match?.confidence ?: DuplicateConfidence.NONE
            tx.isDuplicate = match != null
            tx.duplicateOf = match?.let { describe(it.candidate) }

            // A possible match is a question, not a verdict: two real payments
            // of the same amount on one day are perfectly ordinary, and quietly
            // dropping the second loses a genuine transaction.
            if (match != null && match.confidence != DuplicateConfidence.POSSIBLE) {
                tx.isSelected = false
            }

            if (tx.isSelected) {
                val accepted = ExistingTransaction(
                    amount = tx.amount,
                    date = tx.date,
                    merchant = tx.merchant,
                    currency = tx.currency.code,
                    reference = tx.reference,
                    title = tx.title,
                    dateIsAssumed = tx.dateIsAssumed
                )
                if (isDebit) acceptedExpenses += accepted else acceptedIncomes += accepted
            }
        }
    }

    private fun bestMatch(tx: RawTransaction, candidates: List<ExistingTransaction>): Match? {
        var best: Match? = null

        for (candidate in candidates) {
            val confidence = compare(tx, candidate) ?: continue
            if (best == null || confidence > best.confidence) {
                best = Match(confidence, candidate)
                // Nothing outranks a reference match, so stop looking.
                if (confidence == DuplicateConfidence.CERTAIN) return best
            }
        }
        return best
    }

    /** Null when the two are not the same transaction at all. */
    private fun compare(tx: RawTransaction, candidate: ExistingTransaction): DuplicateConfidence? {
        // A shared reference is conclusive on its own. Amount and date are not
        // consulted: the bank posts a different timestamp from the payment app,
        // and the reference still identifies the same payment.
        if (!tx.reference.isNullOrBlank() && tx.reference.equals(candidate.reference, ignoreCase = true)) {
            return DuplicateConfidence.CERTAIN
        }

        if (abs(candidate.amount - tx.amount) >= AMOUNT_TOLERANCE) return null

        // ₹500 and $500 are not the same payment. The previous implementation
        // compared the numbers and ignored the denomination entirely.
        if (!candidate.currency.equals(tx.currency.code, ignoreCase = true)) return null

        // A date nobody read is not evidence, and must not be allowed to rule a
        // match out. A card bill with no printed date is stamped with the day it
        // was scanned; the statement listing that same purchase carries the real
        // one. Enforcing the window between them rejects the match before the
        // amount or payee are even weighed, and the payment is counted twice.
        val dateIsEvidence = !tx.dateIsAssumed && !candidate.dateIsAssumed

        if (dateIsEvidence && abs(candidate.date - tx.date) > DATE_WINDOW_MILLIS) return null

        val merchantAgrees = MerchantSimilarity.sameMerchant(tx.merchant, candidate.merchant)

        return when {
            // Capped deliberately. Without a trustworthy date this rests on an
            // amount and a name, which two separate visits to the same shop for
            // the same sum would also satisfy — so it is always put to the user
            // rather than acted on.
            !dateIsEvidence -> DuplicateConfidence.POSSIBLE
            merchantAgrees -> DuplicateConfidence.LIKELY
            else -> DuplicateConfidence.POSSIBLE
        }
    }

    /** Names what was matched, so the flag can be explained rather than asserted. */
    private fun describe(candidate: ExistingTransaction): String {
        val name = candidate.merchant?.takeIf { it.isNotBlank() } ?: candidate.title
        return "$name on ${DateUtils.formatDate(candidate.date)}"
    }

    private data class Match(
        val confidence: DuplicateConfidence,
        val candidate: ExistingTransaction
    )
}
