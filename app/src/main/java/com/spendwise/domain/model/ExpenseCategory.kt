package com.spendwise.domain.model

enum class ExpenseCategory(val label: String) {
    Food("Food"),
    Travel("Travel"),
    Shopping("Shopping"),
    Bills("Bills"),
    Health("Health"),
    Entertainment("Entertainment"),
    Other("Other");

    companion object {
        fun fromLabel(label: String): ExpenseCategory =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: Other
    }
}
