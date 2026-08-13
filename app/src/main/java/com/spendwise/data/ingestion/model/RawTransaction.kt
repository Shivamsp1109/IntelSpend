package com.spendwise.data.ingestion.model

import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.ExpenseSource
import com.spendwise.domain.model.TransactionNature

data class RawTransaction(
    val title: String,
    val amount: Double,
    val date: Long,
    val merchant: String? = null,
    val currency: Currency = Currency.INR,
    val category: ExpenseCategory = ExpenseCategory.Other,
    val type: TransactionType = TransactionType.DEBIT,
    val source: ExpenseSource = ExpenseSource.MANUAL,
    val notes: String? = null,
    /** Bank or UPI reference read from the document, when it showed one. */
    val reference: String? = null,
    /**
     * True when [date] was substituted rather than read off the document.
     *
     * A handwritten bill, or one whose date OCR cannot make out, still needs
     * some date to be stored, and today's is the least surprising choice. But
     * the result looks exactly like a date that was read, and downstream code
     * would otherwise treat it as fact — see DuplicateMatcher, where an assumed
     * date is deliberately not allowed to rule a match out.
     */
    val dateIsAssumed: Boolean = false,
    /**
     * Whether this was money spent or money moved.
     *
     * A statement import picks up transfers, card payments and ATM withdrawals
     * as debits alongside real purchases; only Spending counts towards totals.
     */
    val nature: TransactionNature = TransactionNature.Spending,
    var isDuplicate: Boolean = false,
    /** How sure we are that [isDuplicate] is right; drives what the user is shown. */
    var duplicateConfidence: DuplicateConfidence = DuplicateConfidence.NONE,
    /** What it matched, for explaining the flag rather than just asserting it. */
    var duplicateOf: String? = null,
    var isSelected: Boolean = true,
    val confidence: Float = 1.0f,
    val fieldConfidence: FieldConfidence = FieldConfidence()
)

enum class TransactionType { DEBIT, CREDIT }

/**
 * How strong the evidence is that a transaction was already recorded.
 *
 * The distinction exists because the two ends of the scale deserve different
 * treatment. A reference-number match is arithmetic, not judgement, and can be
 * stated plainly. An amount-and-date match with an unrecognisable merchant is a
 * guess, and a wrong guess here silently deletes a real payment from someone's
 * records — so it is surfaced as a question rather than acted on.
 */
enum class DuplicateConfidence {
    /** No match found. */
    NONE,

    /** Same amount, date and direction, but nothing confirms the merchant. */
    POSSIBLE,

    /** Everything agrees, including a recognisably similar merchant. */
    LIKELY,

    /** The bank or UPI reference is identical. This is the same payment. */
    CERTAIN
}
