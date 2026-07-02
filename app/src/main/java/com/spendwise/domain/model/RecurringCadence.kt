package com.spendwise.domain.model

/**
 * Billing cadence for a [RecurringEntry].
 */
enum class RecurringCadence(val label: String) {
    DAILY("Daily"),
    WEEKLY("Weekly"),
    BIWEEKLY("Bi-weekly"),
    MONTHLY("Monthly"),
    QUARTERLY("Quarterly"),
    YEARLY("Yearly");

    companion object {
        fun fromName(name: String): RecurringCadence =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MONTHLY
    }
}
