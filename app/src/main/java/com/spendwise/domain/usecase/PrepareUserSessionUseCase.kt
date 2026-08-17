package com.spendwise.domain.usecase

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.spendwise.data.local.SpendWiseDatabase
import com.spendwise.util.IncomePreferenceStore
import com.spendwise.util.NarrativePreferenceStore
import com.spendwise.util.RecurringReminderState
import com.spendwise.util.SessionAction
import com.spendwise.util.SessionOwnerStore
import com.spendwise.util.SessionOwnership
import com.spendwise.util.SmartExtractionPreferenceStore
import com.spendwise.util.SyncStateStore
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Makes sure the data on this device belongs to whoever is signed in.
 *
 * The local database has no user column: it was built for one account per
 * device, and every query reads whatever is present. So when a second person
 * signs in, they see the first person's income and spending — and the next sync
 * uploads those rows into *their* account on the server, which turns a display
 * bug into permanent contamination of two people's records.
 *
 * Runs before anything reads the database, on every path into a session — both
 * a fresh sign-in and a launch that finds an existing one.
 *
 * Clearing loses nothing that was synced: the server holds every row under the
 * account that owns it, and [RestoreFromServerUseCase] pulls the new user's data
 * back into the now-empty database straight afterwards.
 */
class PrepareUserSessionUseCase @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val database: SpendWiseDatabase,
    private val sessionOwnerStore: SessionOwnerStore,
    private val incomePreferences: IncomePreferenceStore,
    private val narrativePreferences: NarrativePreferenceStore,
    private val smartExtractionPreferences: SmartExtractionPreferenceStore,
    private val reminderState: RecurringReminderState,
    private val syncStateStore: SyncStateStore
) {
    suspend operator fun invoke(): SessionAction {
        val uid = firebaseAuth.currentUser?.uid ?: return SessionAction.Continue

        val action = SessionOwnership.decide(
            ownerUid = sessionOwnerStore.ownerUid,
            signedInUid = uid
        )

        if (action == SessionAction.ClearAndClaim) {
            Log.i(TAG, "A different account signed in; clearing this device's data.")
            clearEverything()
        }

        // Claimed after the clearing, never before. If the wipe fails halfway the
        // owner stays as it was, so the next launch tries again rather than
        // recording this account as the owner of somebody else's leftovers.
        if (action != SessionAction.Continue) {
            sessionOwnerStore.claim(uid)
        }

        return action
    }

    private suspend fun clearEverything() {
        withContext(Dispatchers.IO) {
            // Covers every table at once, including any added later — a list of
            // tables here would silently miss the next entity somebody adds.
            database.clearAllTables()
        }

        // Preferences are not in the database, and every one of these is personal:
        // an income figure, two consents to send financial data to a cloud model,
        // and notification state keyed by row ids that are about to be reused.
        incomePreferences.clearForNewUser()
        narrativePreferences.clearForNewUser()
        smartExtractionPreferences.clearForNewUser()
        reminderState.clearForNewUser()
        // The previous account's sweep says nothing about this one's, and
        // carrying it over would let a brand new session claim its data had
        // already reached the server.
        syncStateStore.clearForNewUser()
    }

    private companion object {
        const val TAG = "PrepareUserSession"
    }
}
