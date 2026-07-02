package com.spendwise.domain.model

/**
 * Charge variability type for a [RecurringEntry].
 */
enum class RecurringType(val label: String) {
    FIXED("Fixed"),
    VARIABLE("Variable"),
    ONE_TIME("One-time");

    companion object {
        fun fromName(name: String): RecurringType =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: FIXED
    }
}
