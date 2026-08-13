package com.spendwise.domain.usecase

import com.spendwise.data.local.toDomain
import com.spendwise.data.remote.ExpenseSyncPayload
import com.spendwise.data.remote.IncomeSyncPayload
import com.spendwise.data.remote.toSyncPayload
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning stored records back into local rows.
 *
 * Both invariants checked here fail silently when broken — no crash, no error,
 * just a history that is wrong in a way the user is unlikely to notice until it
 * matters. That is the whole reason they are pinned.
 */
class RestoreMappingTest {

    private fun storedExpense(
        localId: Int = 42,
        merchant: String? = "Swiggy",
        currency: String = "INR",
        source: String = "PDF",
        reference: String? = "621427195933",
        dateIsAssumed: Boolean = false
    ) = ExpenseSyncPayload(
        uid = "user-1",
        localId = localId,
        title = "Lunch",
        amount = 500.0,
        category = "Food",
        date = 1_786_060_800_000L,
        merchant = merchant,
        currency = currency,
        source = source,
        reference = reference,
        dateIsAssumed = dateIsAssumed
    )

    private fun storedIncome(localId: Int = 7) = IncomeSyncPayload(
        uid = "user-1",
        localId = localId,
        title = "Salary",
        amount = 60_000.0,
        currency = "INR",
        source = "SALARY",
        note = "August",
        date = 1_786_060_800_000L,
        reference = "999888777666",
        dateIsAssumed = false
    )

    /**
     * The server keys rows on (uid, local_id). Letting Room assign a fresh id
     * on the way back in would make the next push look like a different
     * transaction, and the account's history would quietly double.
     */
    @Test
    fun `the server id becomes the local row id`() {
        assertEquals(42, storedExpense(localId = 42).toRestoredEntity().id)
        assertEquals(7, storedIncome(localId = 7).toRestoredEntity().id)
    }

    /**
     * These rows arrived from the server. Marked unsynced, the next sweep would
     * queue every one for upload and push the whole restored history back.
     */
    @Test
    fun `restored rows are already synced`() {
        assertTrue(storedExpense().toRestoredEntity().isSynced)
        assertTrue(storedIncome().toRestoredEntity().isSynced)
    }

    /**
     * A restored device that assumed every date was real would start letting
     * guessed dates rule out duplicate matches again — the exact problem the
     * flag exists to prevent, reintroduced by a reinstall.
     */
    @Test
    fun `the assumed-date flag survives the round trip`() {
        assertTrue(storedExpense(dateIsAssumed = true).toRestoredEntity().dateIsAssumed)
        assertTrue(!storedExpense(dateIsAssumed = false).toRestoredEntity().dateIsAssumed)
    }

    @Test
    fun `the payment reference survives the round trip`() {
        assertEquals("621427195933", storedExpense().toRestoredEntity().reference)
        assertEquals("999888777666", storedIncome().toRestoredEntity().reference)
    }

    /**
     * The point of carrying the reference through sync at all: a restored
     * device can still recognise a statement covering payments it already has.
     */
    @Test
    fun `an expense round trips through sync unchanged`() {
        val original = storedExpense().toRestoredEntity()
        val roundTripped = original.toSyncPayload(uid = "user-1").toRestoredEntity()

        assertEquals(original, roundTripped)
    }

    @Test
    fun `an income round trips through sync unchanged`() {
        val original = storedIncome().toRestoredEntity()
        val roundTripped = original.toSyncPayload(uid = "user-1").toRestoredEntity()

        assertEquals(original, roundTripped)
    }

    @Test
    fun `the rest of the fields are carried across`() {
        val restored = storedExpense().toRestoredEntity().toDomain()

        assertEquals("Lunch", restored.title)
        assertEquals(500.0, restored.amount, 0.0001)
        assertEquals(Currency.INR, restored.currency)
        assertEquals(ExpenseSource.PDF, restored.source)
        assertEquals("Swiggy", restored.merchant)
    }

    /** Older rows predate these columns and come back blank rather than absent. */
    @Test
    fun `blank currency and source fall back rather than becoming empty`() {
        val restored = storedExpense(currency = "", source = "").toRestoredEntity()

        assertEquals(Currency.INR.code, restored.currency)
        assertEquals(ExpenseSource.MANUAL.name, restored.source)
    }

    @Test
    fun `a missing merchant or reference stays missing`() {
        val restored = storedExpense(merchant = null, reference = null).toRestoredEntity()

        assertNull(restored.merchant)
        assertNull(restored.reference)
    }
}
