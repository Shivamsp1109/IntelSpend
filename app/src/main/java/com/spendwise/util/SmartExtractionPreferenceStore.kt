package com.spendwise.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the user has agreed to send documents to the cloud for extraction.
 *
 * Off by default and never flipped on implicitly. Importing a bank statement or
 * receipt means financial data leaves the device, so this stays an explicit
 * choice rather than a quiet default.
 */
interface SmartExtractionPreferenceStore {
    val enabled: StateFlow<Boolean>
    fun setEnabled(value: Boolean)

    /**
     * Forgets the choice when a different account takes over the device.
     *
     * Consent belongs to a person, not a handset. One user agreeing to send
     * their bank statements to a cloud model must never leave the next user
     * opted in to something they were never asked about.
     */
    fun clearForNewUser()
}

@Singleton
class SharedPrefsSmartExtractionPreferenceStore @Inject constructor(
    @ApplicationContext context: Context
) : SmartExtractionPreferenceStore {
    private val preferences =
        context.getSharedPreferences("spendwise_extraction", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    // The in-memory value is reset too, not just the file. This is a singleton
    // that read the file once at construction, so clearing only the file would
    // leave the old answer live for the rest of the process.
    override fun clearForNewUser() {
        preferences.edit().clear().apply()
        _enabled.value = false
    }

    private companion object {
        const val KEY_ENABLED = "smart_extraction_enabled"
    }
}
