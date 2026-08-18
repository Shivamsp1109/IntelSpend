package com.spendwise.domain.model

/**
 * How much investment risk suits the user — kept as three separate answers.
 *
 * Tolerance is how much loss somebody can live with. Capacity is how much loss
 * their finances can absorb without breaking. Need is how much risk the goals
 * they have set actually require. These get routinely collapsed into a single
 * "risk score", and the result describes nobody: a comfortable investor with no
 * reserve and a mortgage has high tolerance and low capacity, and averaging
 * those to "moderate" is true of neither.
 *
 * [userConfirmed] is a gate rather than a flag. Nothing here is inferred from
 * spending habits or holdings and quietly applied — the profile exists only once
 * the user has answered the questions and said yes to the result. Until then the
 * engine treats risk as unknown and withholds anything that depends on it, which
 * is also what a suitability assessment is supposed to mean.
 */
data class RiskAssessment(
    val id: Int = 0,
    val tolerance: RiskLevel? = null,
    val capacity: RiskLevel? = null,
    val need: RiskLevel? = null,
    /** Which set of questions produced this; a reworded set is a different one. */
    val questionnaireVersion: String = QUESTIONNAIRE_VERSION,
    val answers: List<RiskAnswer> = emptyList(),
    val assessmentDate: Long = System.currentTimeMillis(),
    /** What this assessment could not establish, carried with it. */
    val limitations: List<String> = emptyList(),
    val userConfirmed: Boolean = false,
    val isSynced: Boolean = false
) {
    /** Whether anything downstream may read this at all. */
    val isUsable: Boolean
        get() = userConfirmed && (tolerance != null || capacity != null)

    fun isStale(now: Long = System.currentTimeMillis()): Boolean =
        (now - assessmentDate) / 86_400_000 > PROFILE_MAX_AGE_DAYS

    /**
     * Whether the user is comfortable with more risk than they can afford.
     *
     * Surfaced rather than resolved — it is the single most useful thing a risk
     * profile can reveal, and exactly what one combined score would erase.
     */
    val toleranceExceedsCapacity: Boolean
        get() = tolerance != null && capacity != null && tolerance.rank > capacity.rank

    companion object {
        /** Bumped when the questions change: old answers did not answer new ones. */
        const val QUESTIONNAIRE_VERSION = "1.0.0"

        /** Circumstances change; past this the profile is reported as old. */
        const val PROFILE_MAX_AGE_DAYS = 730
    }
}

enum class RiskLevel(val rank: Int, val label: String) {
    LOW(0, "Low"),
    MODERATE(1, "Moderate"),
    HIGH(2, "High");

    companion object {
        fun fromName(name: String): RiskLevel? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/** One answer, kept so a profile can be re-read rather than just re-scored. */
data class RiskAnswer(
    val questionId: String,
    val answer: String
)
