package com.spendwise.data.ingestion.model

enum class ReviewSeverity {
    GREEN,
    YELLOW,
    RED
}

data class FieldConfidence(
    val title: Float = 0.5f,
    val amount: Float = 0.5f,
    val date: Float = 0.5f,
    val merchant: Float = 0.5f,
    val currency: Float = 0.5f,
    val category: Float = 0.5f
) {
    fun needsReview(): Boolean = severity() != ReviewSeverity.GREEN

    fun severity(): ReviewSeverity {
        return when {
            amount < 0.90f || date < 0.85f -> ReviewSeverity.RED
            merchant < 0.75f || category < 0.70f || title < 0.70f || currency < 0.80f -> ReviewSeverity.YELLOW
            else -> ReviewSeverity.GREEN
        }
    }
}
