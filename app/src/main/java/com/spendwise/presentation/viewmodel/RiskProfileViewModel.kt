package com.spendwise.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.domain.model.RiskAnswer
import com.spendwise.domain.model.RiskAssessment
import com.spendwise.domain.model.RiskQuestionnaire
import com.spendwise.domain.repository.RiskProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the risk questionnaire.
 *
 * Answers live here unconfirmed until the user explicitly accepts the result.
 * Saving a draft profile with `userConfirmed = false` is deliberate: the answers
 * survive if they close the screen halfway, and nothing downstream will read
 * them, because every consumer goes through `getConfirmedProfile`.
 */
@HiltViewModel
class RiskProfileViewModel @Inject constructor(
    private val riskProfileRepository: RiskProfileRepository
) : ViewModel() {

    private val draftAnswers = MutableStateFlow<Map<String, String>>(emptyMap())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val uiState: StateFlow<RiskProfileUiState> =
        combine(riskProfileRepository.observeProfile(), draftAnswers) { stored, drafts ->
            // Stored answers seed the form; anything the user has changed in this
            // session takes precedence.
            val merged = stored?.answers.orEmpty()
                .associate { it.questionId to it.answer } + drafts

            val answers = merged.map { (questionId, answer) -> RiskAnswer(questionId, answer) }

            RiskProfileUiState(
                stored = stored,
                answers = merged,
                provisionalTolerance = RiskQuestionnaire.score(
                    answers, RiskQuestionnaire.RiskDimension.TOLERANCE
                ),
                provisionalCapacity = RiskQuestionnaire.score(
                    answers, RiskQuestionnaire.RiskDimension.CAPACITY
                ),
                isLoaded = true
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RiskProfileUiState())

    fun answer(questionId: String, optionId: String) {
        draftAnswers.value = draftAnswers.value + (questionId to optionId)
    }

    /**
     * Stores the answers without treating them as a profile.
     *
     * The distinction the whole feature turns on: a half-finished questionnaire
     * is worth keeping and must not be read as an answer.
     */
    fun saveDraft() {
        viewModelScope.launch { persist(confirmed = false) }
    }

    /** The user has read the result and accepted it. Only now is it usable. */
    fun confirm() {
        viewModelScope.launch {
            val state = uiState.value
            if (state.provisionalTolerance == null && state.provisionalCapacity == null) {
                _message.value = "Answer a few questions first."
                return@launch
            }
            persist(confirmed = true)
            _message.value = "Risk profile saved."
        }
    }

    fun clear() {
        viewModelScope.launch {
            riskProfileRepository.clear()
            draftAnswers.value = emptyMap()
            _message.value = "Risk profile removed."
        }
    }

    private suspend fun persist(confirmed: Boolean) {
        val state = uiState.value
        val answers = state.answers.map { (questionId, answer) -> RiskAnswer(questionId, answer) }

        riskProfileRepository.save(
            RiskAssessment(
                tolerance = state.provisionalTolerance,
                capacity = state.provisionalCapacity,
                // Need comes from the goals, not from this questionnaire, and is
                // filled in by the engine rather than asked for here.
                need = state.stored?.need,
                questionnaireVersion = RiskQuestionnaire.VERSION,
                answers = answers,
                assessmentDate = System.currentTimeMillis(),
                limitations = RiskQuestionnaire.limitationsFor(answers),
                userConfirmed = confirmed
            )
        )
    }

    fun clearMessage() {
        _message.value = null
    }
}

data class RiskProfileUiState(
    val stored: RiskAssessment? = null,
    val answers: Map<String, String> = emptyMap(),
    /** Scored from what has been answered so far, and not yet a profile. */
    val provisionalTolerance: com.spendwise.domain.model.RiskLevel? = null,
    val provisionalCapacity: com.spendwise.domain.model.RiskLevel? = null,
    val isLoaded: Boolean = false
) {
    val answeredCount: Int get() = answers.size
    val totalQuestions: Int get() = RiskQuestionnaire.QUESTIONS.size
    val isComplete: Boolean get() = answeredCount >= totalQuestions

    val isConfirmed: Boolean get() = stored?.userConfirmed == true

    /** Whether the answers so far say the user wants more risk than they can take. */
    val toleranceExceedsCapacity: Boolean
        get() = provisionalTolerance != null && provisionalCapacity != null &&
            provisionalTolerance.rank > provisionalCapacity.rank
}
