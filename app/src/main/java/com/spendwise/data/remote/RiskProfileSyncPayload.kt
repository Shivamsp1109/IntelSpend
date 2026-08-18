package com.spendwise.data.remote

import com.spendwise.data.local.RiskAssessmentEntity
import com.spendwise.data.local.toDomain
import com.spendwise.domain.model.RiskAnswer
import com.spendwise.domain.model.RiskAssessment
import com.spendwise.domain.model.RiskLevel

/**
 * A risk profile on its way to the server.
 *
 * `userConfirmed` is sent as it stands and never coerced to true. The flag is
 * the whole mechanism by which an unfinished questionnaire stays unreadable
 * downstream, and defaulting it on the way out would apply a profile the user
 * never agreed to.
 */
data class RiskProfileSyncPayload(
    val uid: String,
    val riskTolerance: String?,
    val riskCapacity: String?,
    val riskNeed: String?,
    val questionnaireVersion: String,
    val answers: List<RiskAnswerPayload>,
    val assessmentDate: Long,
    val limitations: List<String>,
    val userConfirmed: Boolean
)

data class RiskAnswerPayload(val questionId: String, val answer: String)

fun RiskAssessmentEntity.toSyncPayload(uid: String): RiskProfileSyncPayload {
    val profile = toDomain()
    return RiskProfileSyncPayload(
        uid = uid,
        riskTolerance = profile.tolerance?.name,
        riskCapacity = profile.capacity?.name,
        riskNeed = profile.need?.name,
        questionnaireVersion = profile.questionnaireVersion,
        answers = profile.answers.map { RiskAnswerPayload(it.questionId, it.answer) },
        assessmentDate = profile.assessmentDate,
        limitations = profile.limitations,
        userConfirmed = profile.userConfirmed
    )
}

/** What the server returns; `profile` is null when none has been completed. */
data class RiskProfileResponse(val profile: RiskProfileRestorePayload?)

data class RiskProfileRestorePayload(
    val riskTolerance: String?,
    val riskCapacity: String?,
    val riskNeed: String?,
    val questionnaireVersion: String?,
    val answers: List<RiskAnswerPayload>?,
    val assessmentDate: Long?,
    val limitations: List<String>?,
    val userConfirmed: Boolean?
)

fun RiskProfileRestorePayload.toDomain(): RiskAssessment = RiskAssessment(
    tolerance = riskTolerance?.let { RiskLevel.fromName(it) },
    capacity = riskCapacity?.let { RiskLevel.fromName(it) },
    need = riskNeed?.let { RiskLevel.fromName(it) },
    questionnaireVersion = questionnaireVersion ?: RiskAssessment.QUESTIONNAIRE_VERSION,
    answers = answers.orEmpty().map { RiskAnswer(it.questionId, it.answer) },
    assessmentDate = assessmentDate ?: System.currentTimeMillis(),
    limitations = limitations.orEmpty(),
    // Absent means not confirmed. Never defaulted the other way.
    userConfirmed = userConfirmed == true,
    isSynced = true
)
