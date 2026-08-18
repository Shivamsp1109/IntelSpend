package com.spendwise.domain.model

/**
 * The questions that produce a risk profile.
 *
 * Deliberately split into two sets that are scored separately and never mixed.
 * Tolerance questions ask how somebody would *feel* and *behave*; capacity
 * questions ask what their finances could actually withstand. Blending them
 * produces a single number that describes neither — and the gap between the two
 * is the most useful thing the questionnaire can find.
 *
 * The wording avoids leading. "How would you feel if this fell 20%?" invites the
 * brave answer; asking what someone would actually do gets closer to what they
 * would actually do.
 *
 * Kept in the domain layer rather than the screen because the version is part of
 * the stored profile: a reworded question is a different instrument, and an old
 * answer set must not be read as though it answered the new one.
 */
object RiskQuestionnaire {

    const val VERSION = RiskAssessment.QUESTIONNAIRE_VERSION

    /**
     * Each option carries the level it counts towards. Scores are averaged
     * within a dimension rather than summed, so adding a question later does not
     * silently shift everybody's result.
     */
    data class Option(val id: String, val text: String, val level: RiskLevel)

    data class Question(
        val id: String,
        val dimension: RiskDimension,
        val prompt: String,
        val help: String? = null,
        val options: List<Option>
    )

    enum class RiskDimension { TOLERANCE, CAPACITY }

    val QUESTIONS: List<Question> = listOf(
        Question(
            id = "reaction_to_fall",
            dimension = RiskDimension.TOLERANCE,
            prompt = "Your investments drop 20% over a few months. What do you actually do?",
            help = "Not what you think you should do — what you think you would do.",
            options = listOf(
                Option("sell_all", "Sell, to stop it getting worse", RiskLevel.LOW),
                Option("sell_some", "Sell some of it", RiskLevel.LOW),
                Option("hold", "Leave it alone and wait", RiskLevel.MODERATE),
                Option("buy_more", "Buy more while it's cheaper", RiskLevel.HIGH)
            )
        ),
        Question(
            id = "sleep_test",
            dimension = RiskDimension.TOLERANCE,
            prompt = "How much would a falling balance play on your mind?",
            options = listOf(
                Option("a_lot", "I'd check it constantly and lose sleep", RiskLevel.LOW),
                Option("some", "I'd notice and worry a bit", RiskLevel.MODERATE),
                Option("little", "I'd expect it and carry on", RiskLevel.HIGH)
            )
        ),
        Question(
            id = "experience",
            dimension = RiskDimension.TOLERANCE,
            prompt = "How familiar are you with investments that go up and down?",
            options = listOf(
                Option("none", "Not at all — I've only used savings accounts", RiskLevel.LOW),
                Option("some", "A little — some funds or shares", RiskLevel.MODERATE),
                Option("lots", "Quite familiar — I've been through a few falls", RiskLevel.HIGH)
            )
        ),
        Question(
            id = "dependants",
            dimension = RiskDimension.CAPACITY,
            prompt = "How many people rely on your income?",
            help = "Anyone whose living costs you cover, including yourself.",
            options = listOf(
                Option("many", "Three or more", RiskLevel.LOW),
                Option("some", "One or two besides me", RiskLevel.MODERATE),
                Option("just_me", "Just me", RiskLevel.HIGH)
            )
        ),
        Question(
            id = "horizon",
            dimension = RiskDimension.CAPACITY,
            prompt = "When would you need to draw on this money?",
            help = "A loss matters far more if you have to sell soon after it happens.",
            options = listOf(
                Option("soon", "Within three years", RiskLevel.LOW),
                Option("medium", "Three to seven years", RiskLevel.MODERATE),
                Option("far", "More than seven years away", RiskLevel.HIGH)
            )
        ),
        Question(
            id = "income_security",
            dimension = RiskDimension.CAPACITY,
            prompt = "If your income stopped tomorrow, how long could you manage?",
            options = listOf(
                Option("under_3", "Less than three months", RiskLevel.LOW),
                Option("3_to_6", "Three to six months", RiskLevel.MODERATE),
                Option("over_6", "More than six months", RiskLevel.HIGH)
            )
        )
    )

    /**
     * Scores answers into a level per dimension.
     *
     * Returns null for a dimension with no answers rather than a default. An
     * unanswered dimension is unknown, and "moderate" is an answer — one the
     * engine would go on to grade a portfolio against.
     */
    fun score(answers: List<RiskAnswer>, dimension: RiskDimension): RiskLevel? {
        val relevant = QUESTIONS
            .filter { it.dimension == dimension }
            .mapNotNull { question ->
                val given = answers.firstOrNull { it.questionId == question.id } ?: return@mapNotNull null
                question.options.firstOrNull { it.id == given.answer }?.level
            }

        if (relevant.isEmpty()) return null

        val average = relevant.sumOf { it.rank }.toDouble() / relevant.size
        return when {
            average < 0.67 -> RiskLevel.LOW
            average < 1.34 -> RiskLevel.MODERATE
            else -> RiskLevel.HIGH
        }
    }

    /** What the questionnaire could not establish, carried onto the profile. */
    fun limitationsFor(answers: List<RiskAnswer>): List<String> {
        val notes = mutableListOf<String>()

        RiskDimension.entries.forEach { dimension ->
            val asked = QUESTIONS.count { it.dimension == dimension }
            val answered = QUESTIONS.count { question ->
                question.dimension == dimension && answers.any { it.questionId == question.id }
            }
            if (answered < asked) {
                notes += "${answered} of ${asked} ${dimension.name.lowercase()} questions answered."
            }
        }

        notes += "Based on your own answers rather than a regulated suitability assessment."
        return notes
    }

    val toleranceQuestions: List<Question> get() = QUESTIONS.filter { it.dimension == RiskDimension.TOLERANCE }
    val capacityQuestions: List<Question> get() = QUESTIONS.filter { it.dimension == RiskDimension.CAPACITY }
}
