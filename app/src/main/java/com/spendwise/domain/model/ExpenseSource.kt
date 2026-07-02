package com.spendwise.domain.model

/**
 * Tracks the capture method / entry channel for an [Expense].
 */
enum class ExpenseSource(val label: String) {
    MANUAL("Manual"),
    SMS("SMS"),
    OCR("OCR"),
    PDF("PDF"),
    GMAIL("Gmail"),
    SCREENSHOT("Screenshot");

    companion object {
        fun fromLabel(label: String): ExpenseSource =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: MANUAL
    }
}
