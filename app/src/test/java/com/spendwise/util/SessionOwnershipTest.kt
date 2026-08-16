package com.spendwise.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Deciding whether the data on a device belongs to whoever just signed in.
 *
 * One comparison, and the whole isolation guarantee rests on it. Getting it
 * wrong in one direction shows somebody their own history and then wipes it; in
 * the other it shows them a stranger's income and spending, and uploads those
 * rows into their account on the next sync — contaminating two people's records
 * permanently.
 */
class SessionOwnershipTest {

    private val alice = "uid-alice"
    private val bob = "uid-bob"

    /**
     * A device with no record has no other account the data could belong to —
     * this is a first sign-in, or an upgrade from before ownership was tracked,
     * and in both cases what is here is the signer's own.
     */
    @Test
    fun `an unclaimed device is taken over without clearing`() {
        assertEquals(SessionAction.Claim, SessionOwnership.decide(ownerUid = null, signedInUid = alice))
    }

    @Test
    fun `the same account returning changes nothing`() {
        assertEquals(SessionAction.Continue, SessionOwnership.decide(ownerUid = alice, signedInUid = alice))
    }

    /** The case this exists for, and the one that was broken. */
    @Test
    fun `a different account clears what is here first`() {
        assertEquals(
            SessionAction.ClearAndClaim,
            SessionOwnership.decide(ownerUid = alice, signedInUid = bob)
        )
    }

    /** Signing back in after somebody else used the device is still a switch. */
    @Test
    fun `switching back also clears`() {
        assertEquals(
            SessionAction.ClearAndClaim,
            SessionOwnership.decide(ownerUid = bob, signedInUid = alice)
        )
    }

    /**
     * Firebase uids are case-sensitive and opaque. Treating near-misses as the
     * same account would be a silent failure of the guarantee, so only an exact
     * match counts.
     */
    @Test
    fun `only an exact uid counts as the same account`() {
        assertEquals(
            SessionAction.ClearAndClaim,
            SessionOwnership.decide(ownerUid = "uid-Alice", signedInUid = "uid-alice")
        )
    }
}
