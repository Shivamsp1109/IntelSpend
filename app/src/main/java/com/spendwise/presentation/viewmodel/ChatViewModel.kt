package com.spendwise.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.data.local.ExpenseDao
import com.spendwise.data.remote.ChatDataSource
import com.spendwise.data.remote.ChatRequest
import com.spendwise.data.remote.ChatResult
import com.spendwise.data.remote.ChatTurnPayload
import com.spendwise.domain.model.Currency
import com.spendwise.util.ChatPreferenceStore
import com.spendwise.util.SyncStateStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.TimeZone
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drives the assistant.
 *
 * The consent gate is checked here and nowhere else, before anything is built or
 * sent. Doing it at the data source would mean the request had already been
 * assembled; doing it in the UI would mean a second caller could skip it.
 *
 * The outbound payload carries no financial data. The server holds the records
 * and assembles the assessment itself, so there is no path from this screen to a
 * transaction or a merchant name — not by policy, but because those values are
 * never put into the request.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatDataSource: ChatDataSource,
    private val chatPreferences: ChatPreferenceStore,
    private val syncStateStore: SyncStateStore,
    private val expenseDao: ExpenseDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val enabled: StateFlow<Boolean> = chatPreferences.enabled

    fun setEnabled(value: Boolean) {
        chatPreferences.setEnabled(value)
        if (!value) {
            // Turning it off clears what is on screen. Leaving a conversation
            // visible after consent is withdrawn would suggest it is still live.
            _uiState.value = ChatUiState()
        }
    }

    fun ask(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty() || _uiState.value.isSending) return

        if (!chatPreferences.enabled.value) {
            _uiState.value = _uiState.value.copy(
                error = "Turn the assistant on before asking a question."
            )
            return
        }

        viewModelScope.launch {
            val turns = _uiState.value.turns + ChatTurn(role = ChatRole.USER, text = trimmed)
            _uiState.value = _uiState.value.copy(turns = turns, isSending = true, error = null)

            // How much this device is still holding. The server annotates its
            // answer with it rather than sounding equally sure either way.
            val pending = runCatching { expenseDao.observePendingSyncCount().first() }.getOrDefault(0)

            val result = chatDataSource.ask(
                ChatRequest(
                    message = trimmed,
                    conversationId = _uiState.value.conversationId,
                    // Only the recent exchange, and only what was said. The
                    // server caps this again on arrival.
                    history = turns.dropLast(1).takeLast(HISTORY_TURNS).map { turn ->
                        ChatTurnPayload(
                            role = if (turn.role == ChatRole.USER) "user" else "assistant",
                            content = turn.text
                        )
                    },
                    timezone = TimeZone.getDefault().id,
                    currency = Currency.INR.code,
                    pendingLocalChanges = pending,
                    lastSuccessfulSyncAt = syncStateStore.lastSuccessfulSyncAt.value
                )
            )

            _uiState.value = when (result) {
                is ChatResult.Success -> {
                    val response = result.response
                    _uiState.value.copy(
                        conversationId = response.conversationId ?: _uiState.value.conversationId,
                        turns = _uiState.value.turns + ChatTurn(
                            role = ChatRole.ASSISTANT,
                            text = response.paragraphs.orEmpty().joinToString("\n\n"),
                            traceId = response.traceId,
                            // Shown to the user rather than hidden. An answer the
                            // engine wrote because the assistant's wording was
                            // refused is a different kind of answer.
                            isEngineWorded = response.wordingFallback == true,
                            caveats = response.dataQuality?.caveats.orEmpty()
                        ),
                        suggestions = response.suggestedFollowUps.orEmpty(),
                        isSending = false
                    )
                }
                ChatResult.QuotaExceeded -> _uiState.value.copy(
                    isSending = false,
                    error = "You have used this month's questions. It resets next month."
                )
                is ChatResult.Failed -> _uiState.value.copy(
                    isSending = false,
                    error = result.message
                )
            }
        }
    }

    fun clearConversation() {
        _uiState.value = ChatUiState()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private companion object {
        /** Matches the server's own cap; it truncates again on arrival regardless. */
        const val HISTORY_TURNS = 10
    }
}

enum class ChatRole { USER, ASSISTANT }

data class ChatTurn(
    val role: ChatRole,
    val text: String,
    val traceId: String? = null,
    val isEngineWorded: Boolean = false,
    val caveats: List<String> = emptyList()
)

data class ChatUiState(
    val turns: List<ChatTurn> = emptyList(),
    val conversationId: String? = null,
    val suggestions: List<String> = emptyList(),
    val isSending: Boolean = false,
    val error: String? = null
)
