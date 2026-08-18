package com.spendwise.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the user has agreed to ask questions about their finances.
 *
 * A third separate switch rather than a fourth thing under one "AI features"
 * toggle, for the same reason the other two are separate: they send different
 * things. Extraction uploads a document. The narrative sends period totals. This
 * sends a summary of someone's whole financial position — what they earn, owe,
 * hold and are saving for — and somebody may reasonably want the first two and
 * not this.
 *
 * Off by default and never flipped on implicitly. What the assistant is allowed
 * to see is the kind of decision that should be made once, deliberately, rather
 * than discovered later.
 */
interface ChatPreferenceStore {
    val enabled: StateFlow<Boolean>
    fun setEnabled(value: Boolean)

    /** See SmartExtractionPreferenceStore.clearForNewUser: consent is personal. */
    fun clearForNewUser()
}

@Singleton
class SharedPrefsChatPreferenceStore @Inject constructor(
    @ApplicationContext context: Context
) : ChatPreferenceStore {
    private val preferences =
        context.getSharedPreferences("spendwise_chat", Context.MODE_PRIVATE)

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
        const val KEY_ENABLED = "chat_enabled"
    }
}
