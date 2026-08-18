package com.spendwise.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the device is allowed to claim about how much of it reached the server.
 *
 * The failure this guards is quiet and one-directional. A sweep that uploads
 * most rows and fails on one has not finished, but it is the easiest thing in
 * the world to record success at the end of the loop regardless — and once the
 * watermark says the device is clear, the server's assessment stops annotating
 * itself as possibly incomplete. The user then reads a figure built on partial
 * data with nothing on screen suggesting anything is missing.
 *
 * Recovery from a half-finished sweep therefore means: the watermark does not
 * move, and the next assessment still says so.
 */
class SyncStateStoreTest {

    /** A store with the same contract as the real one, without Android. */
    private class FakeSyncStateStore : SyncStateStore {
        private val _lastSuccessfulSyncAt = MutableStateFlow(0L)
        override val lastSuccessfulSyncAt: StateFlow<Long> = _lastSuccessfulSyncAt.asStateFlow()

        override fun recordSuccessfulSync(at: Long) {
            _lastSuccessfulSyncAt.value = at
        }

        override fun clearForNewUser() {
            _lastSuccessfulSyncAt.value = 0L
        }
    }

    /**
     * The sweep's own rule, extracted so it can be exercised without a worker.
     *
     * Mirrors SyncWorker.doWork: the watermark moves only when nothing failed.
     */
    private fun runSweep(store: SyncStateStore, anyFailure: Boolean, now: Long): Boolean {
        if (!anyFailure) store.recordSuccessfulSync(now)
        return !anyFailure
    }

    @Test
    fun `a clean sweep records when it finished`() {
        val store = FakeSyncStateStore()

        val succeeded = runSweep(store, anyFailure = false, now = 1_000L)

        assertTrue(succeeded)
        assertEquals(1_000L, store.lastSuccessfulSyncAt.value)
    }

    @Test
    fun `a sweep that failed on one row records nothing`() {
        // The case worth protecting. Most of the batch went up, so it is
        // tempting to call it done — but the server's copy is incomplete and
        // the next assessment has to keep saying so.
        val store = FakeSyncStateStore()

        val succeeded = runSweep(store, anyFailure = true, now = 1_000L)

        assertFalse(succeeded)
        assertEquals(0L, store.lastSuccessfulSyncAt.value)
    }

    @Test
    fun `a failed sweep does not roll back an earlier good one`() {
        // The previous clean sweep really did happen, and forgetting it would
        // make every later assessment more pessimistic than the facts warrant.
        val store = FakeSyncStateStore()
        runSweep(store, anyFailure = false, now = 1_000L)

        runSweep(store, anyFailure = true, now = 2_000L)

        assertEquals(1_000L, store.lastSuccessfulSyncAt.value)
    }

    @Test
    fun `a recovering sweep moves the watermark forward`() {
        val store = FakeSyncStateStore()
        runSweep(store, anyFailure = false, now = 1_000L)
        runSweep(store, anyFailure = true, now = 2_000L)

        runSweep(store, anyFailure = false, now = 3_000L)

        assertEquals(3_000L, store.lastSuccessfulSyncAt.value)
    }

    @Test
    fun `never synced is distinguishable from synced at the epoch`() {
        // Zero means never. A real timestamp is never zero, so the two cannot be
        // confused — but a store that defaulted to "now" would let a device that
        // has uploaded nothing claim to be current.
        val store = FakeSyncStateStore()

        assertEquals(0L, store.lastSuccessfulSyncAt.value)
    }

    @Test
    fun `a different account starts with no claim about what was synced`() {
        // The previous account's sweep says nothing about this one's, and
        // carrying it over would let a brand new session assert its data had
        // already reached the server.
        val store = FakeSyncStateStore()
        runSweep(store, anyFailure = false, now = 5_000L)

        store.clearForNewUser()

        assertEquals(0L, store.lastSuccessfulSyncAt.value)
    }
}
