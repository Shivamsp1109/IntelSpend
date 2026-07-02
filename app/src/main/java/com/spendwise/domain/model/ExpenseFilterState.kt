package com.spendwise.domain.model

data class ExpenseFilterState(
    val query: String = "",
    val categories: Set<String> = emptySet(),
    val titles: Set<String> = emptySet(),
    val merchants: Set<String> = emptySet(),
    val currencies: Set<String> = emptySet(),
    val startDate: Long? = null,
    val endDate: Long? = null,
    val sortOrder: ExpenseSortOrder = ExpenseSortOrder.DATE_DESC,
    val pageSize: Int = 10
)

enum class ExpenseSortOrder(val label: String) {
    DATE_DESC("Latest First"),
    DATE_ASC("Oldest First"),
    AMOUNT_DESC("Amount High to Low"),
    AMOUNT_ASC("Amount Low to High")
}
