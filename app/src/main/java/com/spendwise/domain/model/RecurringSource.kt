package com.spendwise.domain.model

/**
 * Where a [RecurringEntry] came from.
 *
 * Worth recording because the two deserve different trust. A commitment the user
 * typed in is a statement of fact; one the app inferred from a repeating pattern
 * is a guess they accepted, and a guess can be wrong in ways worth showing —
 * alongside the confidence that produced it.
 */
enum class RecurringSource {
    MANUAL,
    DETECTED;

    companion object {
        fun fromName(name: String): RecurringSource =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } ?: MANUAL
    }
}
