package com.spendwise.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When this device last got everything it was holding onto the server.
 *
 * The server's copy is a lower bound on what the user has actually recorded.
 * Rows live here until a sweep uploads them, so an assessment built on the
 * server side may be missing whatever this handset has not sent yet — and
 * without being told, the server has no way to know that, so it would present a
 * partial picture as a complete one.
 *
 * Recording the last clean sweep is what lets a request say how current its own
 * inputs are. Paired with the pending-change count, which the DAOs already
 * expose, it turns "here is your position" into "here is your position, and
 * three things had not reached us yet".
 *
 * Only a fully clean sweep counts. A pass that uploaded most rows and failed on
 * one has not finished, and treating it as success would be the exact false
 * reassurance this exists to prevent.
 */
interface SyncStateStore {
    /** Epoch millis of the last sweep that finished with nothing left over; 0 when never. */
    val lastSuccessfulSyncAt: StateFlow<Long>

    fun recordSuccessfulSync(at: Long = System.currentTimeMillis())

    /** See SmartExtractionPreferenceStore.clearForNewUser: this belongs to an account. */
    fun clearForNewUser()
}

@Singleton
class SharedPrefsSyncStateStore @Inject constructor(
    @ApplicationContext context: Context
) : SyncStateStore {
    private val preferences =
        context.getSharedPreferences("spendwise_sync_state", Context.MODE_PRIVATE)

    private val _lastSuccessfulSyncAt =
        MutableStateFlow(preferences.getLong(KEY_LAST_SYNC, NEVER))
    override val lastSuccessfulSyncAt: StateFlow<Long> = _lastSuccessfulSyncAt.asStateFlow()

    override fun recordSuccessfulSync(at: Long) {
        preferences.edit().putLong(KEY_LAST_SYNC, at).apply()
        _lastSuccessfulSyncAt.value = at
    }

    override fun clearForNewUser() {
        preferences.edit().clear().apply()
        _lastSuccessfulSyncAt.value = NEVER
    }

    private companion object {
        const val KEY_LAST_SYNC = "last_successful_sync_at"

        /** Distinguishable from a real timestamp, and never mistaken for "just now". */
        const val NEVER = 0L
    }
}
