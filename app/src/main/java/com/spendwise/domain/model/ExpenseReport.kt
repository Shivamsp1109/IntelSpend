package com.spendwise.domain.model

/** File formats a report can be written as. */
enum class ReportFormat(val mimeType: String, val extension: String) {
    CSV("text/csv", "csv"),
    PDF("application/pdf", "pdf")
}

/**
 * Everything a saved report contains, assembled before any file is opened.
 *
 * Built from the same snapshot the screen is showing and the transactions
 * behind it, so an exported total can never disagree with the total the user
 * was looking at when they pressed export.
 */
data class ExpenseReport(
    val periodLabel: String,
    val currency: Currency,
    val generatedAt: Long,
    val summary: SpendingSummary,
    val byCategory: List<Pair<ExpenseCategory, Double>>,
    val topMerchants: List<MerchantSpend>,
    val transactions: List<Expense>
) {
    /** A filename stem safe on every filesystem: 'IntelSpend-August-2026'. */
    val fileNameStem: String
        get() = "IntelSpend-" + periodLabel
            .replace(UNSAFE_FILENAME_CHARS, "-")
            .trim('-')

    private companion object {
        val UNSAFE_FILENAME_CHARS = Regex("""[^A-Za-z0-9]+""")
    }
}
