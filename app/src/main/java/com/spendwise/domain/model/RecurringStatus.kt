package com.spendwise.domain.model

/**
 * Where a commitment is in its life.
 *
 * Deleting is not a substitute for any of these. A gym membership paused over
 * the winter should stop generating reminders and stop counting towards what the
 * user owes each month, but its history is the reason the app knows what it
 * costs — throwing that away to express "not right now" would also lose the
 * ability to bring it back, and would let detection re-suggest it from scratch.
 */
enum class RecurringStatus(val label: String) {

    /** Live: counted in commitments, and reminded about. */
    ACTIVE("Active"),

    /**
     * Temporarily stopped. Excluded from totals and reminders, kept in full so it
     * can be resumed without losing what it costs or when it last went out.
     */
    PAUSED("Paused"),

    /**
     * Finished for good — a loan paid off, a subscription cancelled.
     *
     * Distinct from deleting it: the payments already made are real history and
     * still belong in past months, they just say nothing about future ones.
     */
    ENDED("Ended");

    /** Whether this should be counted and reminded about now. */
    val isLive: Boolean get() = this == ACTIVE

    companion object {
        fun fromName(name: String): RecurringStatus =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: ACTIVE
    }
}
