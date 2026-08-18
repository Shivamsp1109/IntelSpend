package com.spendwise.domain.model

/**
 * What the target amount on a goal actually means.
 *
 * The ambiguity this resolves is real and easy to miss. "₹20,00,000 for a car in
 * three years" may be today's showroom price, which has to be grown to what the
 * car will cost in 2029, or the figure the user already worked out for 2029,
 * which must not be grown again. Inflating the second overstates the goal by
 * years of compounding and would report a perfectly reachable target as out of
 * reach; failing to inflate the first understates it by the same.
 *
 * There is no safe default, so the app asks.
 */
enum class GoalAmountBasis(val label: String, val description: String) {
    TODAYS_MONEY(
        "Today's price",
        "What it would cost right now. We'll allow for prices rising by then."
    ),
    NOMINAL_FUTURE(
        "What it'll cost then",
        "You've already worked out the future figure, so we'll use it as is."
    ),
    MANUALLY_FIXED(
        "Exactly this amount",
        "Use this number unchanged, whatever happens to prices."
    );

    /** Whether the engine should grow this target towards its date. */
    val inflates: Boolean get() = this == TODAYS_MONEY

    companion object {
        fun fromName(name: String): GoalAmountBasis =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: TODAYS_MONEY
    }
}

/** Which goal gives way when they cannot all be funded. */
enum class GoalPriority(val label: String) {
    ESSENTIAL("Essential"),
    IMPORTANT("Important"),
    NICE_TO_HAVE("Nice to have");

    companion object {
        fun fromName(name: String): GoalPriority =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: IMPORTANT
    }
}

/**
 * What can move if the goal does not fit.
 *
 * Asked because it decides what the app is allowed to suggest. Proposing a later
 * date for a school fee due in April is advice that cannot be taken, and one
 * suggestion like that makes every other one look equally unconsidered.
 */
enum class GoalFlexibility(val label: String) {
    DATE_FLEXIBLE("The date could move"),
    AMOUNT_FLEXIBLE("The amount could change"),
    BOTH_FLEXIBLE("Either could change"),
    FIXED("Neither can change");

    companion object {
        fun fromName(name: String): GoalFlexibility =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: BOTH_FLEXIBLE
    }
}

/** Mirrors RecurringStatus: a paused goal stops competing without being lost. */
enum class GoalStatusLifecycle(val label: String) {
    ACTIVE("Active"),
    PAUSED("Paused"),
    ABANDONED("Abandoned"),
    ACHIEVED("Achieved");

    val isLive: Boolean get() = this == ACTIVE

    companion object {
        fun fromName(name: String): GoalStatusLifecycle =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: ACTIVE
    }
}
