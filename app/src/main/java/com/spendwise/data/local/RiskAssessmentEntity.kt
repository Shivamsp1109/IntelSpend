package com.spendwise.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.spendwise.domain.model.RiskAnswer
import com.spendwise.domain.model.RiskAssessment
import com.spendwise.domain.model.RiskLevel

/**
 * The user's risk profile. One row per device, replaced on re-assessment.
 *
 * The three levels are nullable strings rather than an enum with a default,
 * because a default here would be a claim. "Moderate" is not a safe placeholder
 * for an unanswered question — it is an answer, and one the engine would go on
 * to grade a portfolio against.
 *
 * Answers are stored as a delimited string rather than a relation. There are at
 * most a couple of dozen, they are only ever read together with the row that
 * owns them, and a join table would be more machinery than the data justifies.
 */
@Entity(tableName = "risk_assessments")
data class RiskAssessmentEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val tolerance: String? = null,
    val capacity: String? = null,
    val need: String? = null,
    val questionnaireVersion: String = RiskAssessment.QUESTIONNAIRE_VERSION,
    val answers: String = "",
    val assessmentDate: Long,
    val limitations: String = "",
    val userConfirmed: Boolean = false,
    val isSynced: Boolean = false
) {
    companion object {
        /** A person has one risk profile; the fixed key makes that structural. */
        const val SINGLETON_ID = 1

        /**
         * ASCII record and unit separators, written as escapes.
         *
         * A comma or pipe would eventually appear inside somebody's own answer
         * and split one into two; these cannot be typed into a text field.
         */
        const val RECORD_SEPARATOR = "\u001E"
        const val FIELD_SEPARATOR = "\u001F"
    }
}

private fun String.splitRecords(): List<String> =
    if (isBlank()) emptyList() else split(RiskAssessmentEntity.RECORD_SEPARATOR)

fun RiskAssessmentEntity.toDomain(): RiskAssessment = RiskAssessment(
    id = id,
    tolerance = tolerance?.let { RiskLevel.fromName(it) },
    capacity = capacity?.let { RiskLevel.fromName(it) },
    need = need?.let { RiskLevel.fromName(it) },
    questionnaireVersion = questionnaireVersion,
    answers = answers.splitRecords().mapNotNull { record ->
        val parts = record.split(RiskAssessmentEntity.FIELD_SEPARATOR)
        if (parts.size == 2) RiskAnswer(parts[0], parts[1]) else null
    },
    assessmentDate = assessmentDate,
    limitations = limitations.splitRecords(),
    userConfirmed = userConfirmed,
    isSynced = isSynced
)

fun RiskAssessment.toEntity(): RiskAssessmentEntity = RiskAssessmentEntity(
    id = RiskAssessmentEntity.SINGLETON_ID,
    tolerance = tolerance?.name,
    capacity = capacity?.name,
    need = need?.name,
    questionnaireVersion = questionnaireVersion,
    answers = answers.joinToString(RiskAssessmentEntity.RECORD_SEPARATOR) {
        "${it.questionId}${RiskAssessmentEntity.FIELD_SEPARATOR}${it.answer}"
    },
    assessmentDate = assessmentDate,
    limitations = limitations.joinToString(RiskAssessmentEntity.RECORD_SEPARATOR),
    userConfirmed = userConfirmed,
    isSynced = isSynced
)
