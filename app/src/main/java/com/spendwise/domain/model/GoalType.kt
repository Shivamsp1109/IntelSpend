package com.spendwise.domain.model

/**
 * Categories for savings [Goal] entries.
 */
enum class GoalType(val label: String) {
    EMERGENCY_FUND("Emergency Fund"),
    VACATION("Vacation"),
    HOME("Home"),
    VEHICLE("Vehicle"),
    EDUCATION("Education"),
    RETIREMENT("Retirement"),
    CUSTOM("Custom");

    companion object {
        fun fromName(name: String): GoalType =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: CUSTOM
    }
}
