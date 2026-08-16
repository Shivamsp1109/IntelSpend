package com.spendwise.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which account the data on this device belongs to.
 *
 * The local database has no user column and never has — it was built on the
 * assumption of one account per device, and every query reads whatever is there.
 * That assumption breaks the moment somebody signs out and somebody else signs
 * in: the second person sees the first person's income and spending, and worse,
 * the next sync uploads those rows into the second person's account on the
 * server. Recording the owner is what makes that detectable.
 *
 * Deliberately not in the encrypted database. It has to be readable before the
 * database is opened and it must survive the database being cleared, which is
 * the very operation it exists to trigger.
 */
@Singleton
class SessionOwnerStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences =
        context.getSharedPreferences("spendwise_session_owner", Context.MODE_PRIVATE)

    /** Null on a device that has never been signed into. */
    val ownerUid: String?
        get() = preferences.getString(KEY_OWNER_UID, null)

    fun claim(uid: String) {
        preferences.edit().putString(KEY_OWNER_UID, uid).apply()
    }

    private companion object {
        const val KEY_OWNER_UID = "owner_uid"
    }
}

/**
 * Decides what has to happen before a signed-in account may use this device.
 *
 * Pure, so the rule can be stated and tested on its own — it is the whole of the
 * isolation guarantee, and it is one comparison that must not be got wrong.
 */
object SessionOwnership {

    fun decide(ownerUid: String?, signedInUid: String): SessionAction = when (ownerUid) {
        // Never signed in, or the owner record was lost. Whatever is here belongs
        // to whoever is signing in now — on a device with no record there is no
        // other account it could belong to.
        null -> SessionAction.Claim

        signedInUid -> SessionAction.Continue

        // Somebody else's data. It is on the server under their account, so
        // clearing it here loses nothing that was ever synced, and leaving it
        // would show it to the wrong person and then upload it under their name.
        else -> SessionAction.ClearAndClaim
    }
}

enum class SessionAction {
    /** First sign-in on this device: take ownership of what is here. */
    Claim,

    /** The same account as last time; nothing to do. */
    Continue,

    /** A different account: wipe local data before this session starts. */
    ClearAndClaim
}
