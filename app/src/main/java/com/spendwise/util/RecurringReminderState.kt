package com.spendwise.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What this device has already told the user about.
 *
 * Deliberately not synced, and deliberately not a column on the commitment.
 * Whether a notification has been shown is a fact about a handset, not about the
 * account: someone with a phone and a tablet should be reminded on both, and
 * syncing this would mean the second device silently swallows every reminder the
 * first one happened to show first.
 *
 * It is also disposable. Losing it costs at most one repeated notification,
 * which is a far better failure than the alternative — a schema migration and a
 * sync round trip for something that only prevents a duplicate.
 */
@Singleton
class RecurringReminderState @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences =
        context.getSharedPreferences("spendwise_recurring_reminders", Context.MODE_PRIVATE)

    /** The due date this commitment was last announced for, if any. */
    fun lastRemindedDueDate(recurringId: Int): Long? =
        preferences.getLong(dueKey(recurringId), 0L).takeIf { it > 0L }

    fun markReminded(recurringId: Int, dueDate: Long) {
        preferences.edit().putLong(dueKey(recurringId), dueDate).apply()
    }

    /**
     * Detected patterns already put in front of the user.
     *
     * Kept by signature rather than by count: a count would go on announcing
     * "3 payments found" every week for the same three, and would say nothing
     * when one is accepted and a different one appears.
     */
    fun announcedCandidates(): Set<String> =
        preferences.getStringSet(KEY_ANNOUNCED, emptySet()).orEmpty()

    fun markAnnounced(signatures: Set<String>) {
        // Replaced rather than accumulated, so signatures for candidates that no
        // longer exist do not pile up forever. Anything still detectable is in
        // the set being written.
        preferences.edit().putStringSet(KEY_ANNOUNCED, signatures).apply()
    }

    private fun dueKey(recurringId: Int) = "due_$recurringId"

    private companion object {
        const val KEY_ANNOUNCED = "announced_candidates"
    }
}
