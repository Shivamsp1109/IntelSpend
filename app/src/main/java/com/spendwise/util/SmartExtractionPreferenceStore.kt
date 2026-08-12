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

    private companion object {
        const val KEY_ENABLED = "smart_extraction_enabled"
    }
}
