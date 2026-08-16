package com.spendwise.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the user has agreed to have periods summarised by the cloud model.
 *
 * Kept separate from [SmartExtractionPreferenceStore] rather than folded into
 * one "AI features" switch, because the two send different things. Extraction
 * uploads a whole document; this sends totals and a few merchant names. Someone
 * may reasonably want one and not the other, and a single toggle would hide
 * that choice.
 *
 * Off by default, like extraction, and never flipped on implicitly.
 */
interface NarrativePreferenceStore {
    val enabled: StateFlow<Boolean>
    fun setEnabled(value: Boolean)

    /** See [SmartExtractionPreferenceStore.clearForNewUser]: consent is personal. */
    fun clearForNewUser()
}

@Singleton
class SharedPrefsNarrativePreferenceStore @Inject constructor(
    @ApplicationContext context: Context
) : NarrativePreferenceStore {
    private val preferences =
        context.getSharedPreferences("spendwise_narrative", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    override fun clearForNewUser() {
        preferences.edit().clear().apply()
        _enabled.value = false
    }

    private companion object {
        const val KEY_ENABLED = "narrative_enabled"
    }
}
