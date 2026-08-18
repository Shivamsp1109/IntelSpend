package com.spendwise.domain.model

/**
 * A policy the user holds.
 *
 * Insurance is the one part of a financial picture where **absence is the
 * finding**, and that shapes how this is collected. Nothing here can be inferred
 * from transactions: a premium leaving the account proves a policy exists, not
 * what it would pay out, and the payout is the only figure a gap analysis needs.
 *
 * So the sum assured is asked for directly, and a household with nothing
 * recorded is reported as unknown rather than uncovered — telling somebody they
 * have no life cover when they simply have not typed it in would be alarming and
 * wrong, and telling them everything is fine on the same evidence would be worse.
 */
data class InsurancePolicy(
    val id: Int = 0,
    val label: String,
    val type: InsuranceType,
    val provider: String? = null,
    /** What it would pay out. */
    val sumAssured: Double,
    val currency: Currency = Currency.INR,
    val premiumAmount: Double? = null,
    val premiumCadence: PremiumCadence = PremiumCadence.YEARLY,
    /** When cover ends. Past this date it is no longer counted as cover. */
    val policyEndDate: Long? = null,
    /**
     * Whether a nominee is named. Tri-state on purpose: no nominee is a real
     * problem worth flagging, and not knowing is not — collapsing the second
     * into the first would warn about something nobody has established.
     */
    val nomineeSet: Boolean? = null,
    val isSynced: Boolean = false
) {
    fun hasLapsed(now: Long = System.currentTimeMillis()): Boolean =
        policyEndDate != null && policyEndDate < now
}

enum class InsuranceType(val label: String, val isLifeCover: Boolean = false) {
    TERM_LIFE("Term life", isLifeCover = true),
    WHOLE_LIFE("Whole life", isLifeCover = true),
    ENDOWMENT("Endowment", isLifeCover = true),
    ULIP("ULIP", isLifeCover = true),
    HEALTH("Health"),
    CRITICAL_ILLNESS("Critical illness"),
    PERSONAL_ACCIDENT("Personal accident"),
    MOTOR("Motor"),
    HOME("Home"),
    TRAVEL("Travel"),
    OTHER("Other");

    companion object {
        fun fromName(name: String): InsuranceType =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: OTHER
    }
}

enum class PremiumCadence(val label: String) {
    MONTHLY("Monthly"),
    QUARTERLY("Quarterly"),
    HALF_YEARLY("Half-yearly"),
    YEARLY("Yearly"),
    SINGLE("One-off");

    companion object {
        fun fromName(name: String): PremiumCadence =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: YEARLY
    }
}
