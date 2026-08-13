package com.spendwise.data.ingestion.duplicate

import com.spendwise.data.ingestion.model.DuplicateConfidence
import com.spendwise.data.ingestion.model.RawTransaction
import com.spendwise.data.ingestion.model.TransactionType
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Duplicate detection across documents.
 *
 * The case driving all of this: a UPI payment is screenshotted and imported on
 * the day, then the bank statement covering it is imported a week later. The
 * two records agree on the amount and little else — the payee is written
 * differently by the app and by the bank, and the posting timestamp differs.
 *
 * The tests come in pairs on purpose. Every rule that catches a duplicate is
 * matched by one proving it does not catch something innocent, because the two
 * failure modes are not equally recoverable: a duplicate that slips through is
 * visible in the list and can be deleted, while a wrongly merged payment is
 * gone from the user's records with nothing to indicate it was ever there.
 */
class DuplicateMatcherTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    private fun day(day: Int, hour: Int = 12): Long =
        LocalDate.of(2026, 8, day).atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()

    private fun incoming(
        amount: Double = 500.0,
        date: Long = day(13),
        merchant: String? = "Swiggy",
        reference: String? = null,
        currency: Currency = Currency.INR,
        type: TransactionType = TransactionType.DEBIT,
        dateIsAssumed: Boolean = false
    ) = RawTransaction(
        title = merchant ?: "Payment",
        amount = amount,
        date = date,
        merchant = merchant,
        currency = currency,
        type = type,
        source = ExpenseSource.PDF,
        reference = reference,
        dateIsAssumed = dateIsAssumed
    )

    private fun existing(
        amount: Double = 500.0,
        date: Long = day(13),
        merchant: String? = "Swiggy",
        reference: String? = null,
        currency: Currency = Currency.INR,
        dateIsAssumed: Boolean = false
    ) = ExistingTransaction(
        amount = amount,
        date = date,
        merchant = merchant,
        currency = currency.code,
        reference = reference,
        title = merchant ?: "Payment",
        dateIsAssumed = dateIsAssumed
    )

    private fun flag(
        transactions: List<RawTransaction>,
        expenses: List<ExistingTransaction> = emptyList(),
        incomes: List<ExistingTransaction> = emptyList()
    ) = DuplicateMatcher.flag(transactions, expenses, incomes)

    // ── The scenario this was built for ───────────────────────────────────────

    /**
     * ₹500 screenshotted on the 13th, then the same payment appearing in a bank
     * statement imported on the 19th. The names are written the way each source
     * actually writes them.
     */
    @Test
    fun `a statement row matching an earlier screenshot is caught by reference`() {
        val fromScreenshot = existing(
            merchant = "Swiggy",
            date = day(13, hour = 14),
            reference = "621427195933"
        )
        val fromStatement = incoming(
            merchant = "SWIGGY LIMITED",
            // The bank posted it the next morning.
            date = day(14, hour = 2),
            reference = "621427195933"
        )

        flag(listOf(fromStatement), expenses = listOf(fromScreenshot))

        assertTrue(fromStatement.isDuplicate)
        assertEquals(DuplicateConfidence.CERTAIN, fromStatement.duplicateConfidence)
        assertFalse("a certain duplicate must not be imported again", fromStatement.isSelected)
    }

    /** The same scenario when neither document exposed a reference. */
    @Test
    fun `the same payment is still caught on amount date and payee alone`() {
        val fromScreenshot = existing(merchant = "Swiggy", date = day(13, hour = 14))
        val fromStatement = incoming(merchant = "PAYTM*SWIGGY", date = day(14, hour = 2))

        flag(listOf(fromStatement), expenses = listOf(fromScreenshot))

        assertEquals(DuplicateConfidence.LIKELY, fromStatement.duplicateConfidence)
        assertFalse(fromStatement.isSelected)
    }

    /** Banks post over weekends, so the window has to survive a few days' lag. */
    @Test
    fun `a payment posted three days later is still matched`() {
        val screenshot = existing(date = day(13))
        val statement = incoming(date = day(16))

        flag(listOf(statement), expenses = listOf(screenshot))

        assertTrue(statement.isDuplicate)
    }

    @Test
    fun `a payment a fortnight apart is a different transaction`() {
        val screenshot = existing(date = day(1))
        val statement = incoming(date = day(15))

        flag(listOf(statement), expenses = listOf(screenshot))

        assertFalse(statement.isDuplicate)
        assertTrue(statement.isSelected)
    }

    // ── Bills with no readable date ───────────────────────────────────────────

    /**
     * A card bill with no printed date — handwritten, or one OCR could not read
     * — scanned a week after the purchase, then the statement listing that same
     * purchase.
     *
     * The bill was stamped with the day it was scanned, so the two dates are
     * nowhere near each other. Enforcing the window here would reject the match
     * before the amount or payee were weighed at all, and the purchase would be
     * counted twice with nothing said about it. A card purchase has no UPI
     * reference to fall back on, so this is the only thing that catches it.
     */
    @Test
    fun `a bill with an assumed date still matches its statement row`() {
        val scannedLate = existing(
            amount = 1_250.0,
            merchant = "Third Wave Coffee",
            date = day(20),
            dateIsAssumed = true
        )
        val fromStatement = incoming(
            amount = 1_250.0,
            merchant = "THIRD WAVE COFFEE BANGALORE",
            date = day(5)
        )

        flag(listOf(fromStatement), expenses = listOf(scannedLate))

        assertTrue("a guessed date must not veto the match", fromStatement.isDuplicate)
        assertEquals(DuplicateConfidence.POSSIBLE, fromStatement.duplicateConfidence)
    }

    /**
     * And it stays a question. Without a trustworthy date this rests on an
     * amount and a name, which two separate visits to the same shop would also
     * satisfy — so the user decides, rather than losing a real payment.
     */
    @Test
    fun `an assumed-date match is never auto-deselected`() {
        val scannedLate = existing(date = day(20), dateIsAssumed = true)
        val fromStatement = incoming(date = day(5))

        flag(listOf(fromStatement), expenses = listOf(scannedLate))

        assertTrue(fromStatement.isSelected)
    }

    /** It works whichever side lacked the date. */
    @Test
    fun `an assumed date on the incoming side works the same way`() {
        val stored = existing(date = day(5))
        val scannedBill = incoming(date = day(20), dateIsAssumed = true)

        flag(listOf(scannedBill), expenses = listOf(stored))

        assertEquals(DuplicateConfidence.POSSIBLE, scannedBill.duplicateConfidence)
    }

    /**
     * The counterpart that keeps this from swallowing everything: when both
     * dates were genuinely read, the window is still enforced, so two real
     * payments of the same amount weeks apart stay separate.
     */
    @Test
    fun `real dates still enforce the window`() {
        val stored = existing(date = day(5))
        val muchLater = incoming(date = day(20))

        flag(listOf(muchLater), expenses = listOf(stored))

        assertFalse("a read date is evidence and must still apply", muchLater.isDuplicate)
        assertTrue(muchLater.isSelected)
    }

    /** An assumed date relaxes the window, not the other checks. */
    @Test
    fun `an assumed date does not excuse a different amount or currency`() {
        val stored = existing(amount = 500.0, dateIsAssumed = true, date = day(20))

        val differentAmount = incoming(amount = 900.0, date = day(5))
        val differentCurrency = incoming(amount = 500.0, currency = Currency.USD, date = day(5))

        flag(listOf(differentAmount), expenses = listOf(stored))
        flag(listOf(differentCurrency), expenses = listOf(stored))

        assertFalse(differentAmount.isDuplicate)
        assertFalse(differentCurrency.isDuplicate)
    }

    /**
     * A reference still outranks everything. If both documents happen to carry
     * one, the guessed date is irrelevant and the match is stated as certain.
     */
    @Test
    fun `a reference still wins over an assumed date`() {
        val stored = existing(date = day(20), dateIsAssumed = true, reference = "621427195933")
        val incomingRow = incoming(date = day(5), reference = "621427195933")

        flag(listOf(incomingRow), expenses = listOf(stored))

        assertEquals(DuplicateConfidence.CERTAIN, incomingRow.duplicateConfidence)
        assertFalse(incomingRow.isSelected)
    }

    // ── Not merging things that only look alike ───────────────────────────────

    /**
     * Two genuine ₹500 payments on one day, to payees that cannot be reconciled.
     * Flagged for the user to look at, but left selected — dropping one would
     * silently lose a real payment.
     */
    @Test
    fun `an unrecognisable payee is raised as a question not a verdict`() {
        val existingRow = existing(merchant = "Blinkit")
        val incomingRow = incoming(merchant = "Metro Card Recharge")

        flag(listOf(incomingRow), expenses = listOf(existingRow))

        assertEquals(DuplicateConfidence.POSSIBLE, incomingRow.duplicateConfidence)
        assertTrue("a possible duplicate must stay selected", incomingRow.isSelected)
        assertTrue(incomingRow.isDuplicate)
    }

    /** The previous implementation compared amounts and ignored the currency. */
    @Test
    fun `the same number in a different currency is not a duplicate`() {
        val rupees = existing(amount = 500.0, currency = Currency.INR)
        val dollars = incoming(amount = 500.0, currency = Currency.USD)

        flag(listOf(dollars), expenses = listOf(rupees))

        assertFalse(dollars.isDuplicate)
    }

    /** A ₹500 refund is not the ₹500 payment that preceded it. */
    @Test
    fun `a credit is never matched against a debit`() {
        val payment = existing(amount = 500.0, merchant = "Swiggy")
        val refund = incoming(amount = 500.0, merchant = "Swiggy", type = TransactionType.CREDIT)

        flag(listOf(refund), expenses = listOf(payment), incomes = emptyList())

        assertFalse(refund.isDuplicate)
        assertTrue(refund.isSelected)
    }

    @Test
    fun `a different amount is never a duplicate`() {
        val existingRow = existing(amount = 500.0)
        val incomingRow = incoming(amount = 500.5)

        flag(listOf(incomingRow), expenses = listOf(existingRow))

        assertFalse(incomingRow.isDuplicate)
    }

    // ── Within one import ─────────────────────────────────────────────────────

    /** A statement that lists the same row twice, or two overlapping pages. */
    @Test
    fun `a row repeated inside one import is caught`() {
        val first = incoming(reference = "621427195933")
        val second = incoming(reference = "621427195933")

        flag(listOf(first, second))

        assertTrue("the first occurrence is kept", first.isSelected)
        assertFalse("the repeat is not", second.isSelected)
        assertEquals(DuplicateConfidence.CERTAIN, second.duplicateConfidence)
    }

    /**
     * Two genuinely separate payments to the same shop on one day — a coffee in
     * the morning and another in the afternoon, both listed by the statement.
     *
     * The document is asserting there were two, and buying coffee twice is
     * ordinary while a statement repeating a row is not. So within one import
     * this is not flagged at all: raising it would train the user to dismiss
     * the warning.
     */
    @Test
    fun `two real payments to one shop on one day both survive`() {
        val morning = incoming(amount = 120.0, merchant = "Third Wave", date = day(13, hour = 9))
        val afternoon = incoming(amount = 120.0, merchant = "Third Wave", date = day(13, hour = 17))

        flag(listOf(morning, afternoon))

        assertTrue(morning.isSelected)
        assertTrue(afternoon.isSelected)
        assertEquals(DuplicateConfidence.NONE, afternoon.duplicateConfidence)
    }

    /**
     * The counterpart: the same pair measured against what is already stored.
     * Here a familiar payee and amount is the shape a re-import takes, so it is
     * flagged.
     */
    @Test
    fun `a matching payee and amount already on record is flagged`() {
        val stored = existing(amount = 120.0, merchant = "Third Wave", date = day(13, hour = 9))
        val reimported = incoming(amount = 120.0, merchant = "Third Wave", date = day(13, hour = 17))

        flag(listOf(reimported), expenses = listOf(stored))

        assertEquals(DuplicateConfidence.LIKELY, reimported.duplicateConfidence)
        assertFalse(reimported.isSelected)
    }

    /**
     * A weak match against stored data must not hide a certain one inside the
     * batch.
     *
     * The statement lists the same payment twice — same reference — while an
     * unrelated payment of the same amount happens to sit in the database. The
     * stored row is only a possible match and does not deselect anything; if it
     * is allowed to settle the question, the genuine repeat sails through.
     */
    @Test
    fun `a weak stored match does not mask a certain repeat in the batch`() {
        val unrelated = existing(amount = 500.0, merchant = "Metro Card Recharge", date = day(13))
        val first = incoming(amount = 500.0, merchant = "Swiggy", reference = "621427195933")
        val repeat = incoming(amount = 500.0, merchant = "Swiggy", reference = "621427195933")

        flag(listOf(first, repeat), expenses = listOf(unrelated))

        assertEquals(DuplicateConfidence.CERTAIN, repeat.duplicateConfidence)
        assertFalse("the repeat must not be imported", repeat.isSelected)
    }

    // ── Explaining the flag ───────────────────────────────────────────────────

    @Test
    fun `a flagged transaction names what it matched`() {
        val existingRow = existing(merchant = "Swiggy", date = day(13))
        val incomingRow = incoming(merchant = "Swiggy", date = day(13))

        flag(listOf(incomingRow), expenses = listOf(existingRow))

        assertTrue(incomingRow.duplicateOf!!.contains("Swiggy"))
    }

    @Test
    fun `an unmatched transaction carries no explanation`() {
        val incomingRow = incoming()

        flag(listOf(incomingRow))

        assertEquals(DuplicateConfidence.NONE, incomingRow.duplicateConfidence)
        assertEquals(null, incomingRow.duplicateOf)
        assertTrue(incomingRow.isSelected)
    }

    /**
     * A reference match wins even when nothing else lines up. Statements
     * occasionally restate an amount — a partial refund, a corrected fee — and
     * the reference is what still identifies the payment.
     */
    @Test
    fun `a reference match outranks a disagreeing amount`() {
        val existingRow = existing(amount = 500.0, reference = "621427195933", merchant = "Swiggy")
        val incomingRow = incoming(amount = 450.0, reference = "621427195933", merchant = "Unknown")

        flag(listOf(incomingRow), expenses = listOf(existingRow))

        assertEquals(DuplicateConfidence.CERTAIN, incomingRow.duplicateConfidence)
    }

    @Test
    fun `different references on matching amounts are separate payments`() {
        val existingRow = existing(reference = "621427195933", merchant = "Swiggy")
        val incomingRow = incoming(reference = "999999999999", merchant = "Swiggy")

        flag(listOf(incomingRow), expenses = listOf(existingRow))

        // Amount, date and payee still line up, so it is worth raising — but
        // the references disagree, so it is not stated as certain.
        assertEquals(DuplicateConfidence.LIKELY, incomingRow.duplicateConfidence)
    }
}
