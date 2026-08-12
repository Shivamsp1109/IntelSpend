package com.spendwise.domain.model

/** How an insight should read — colouring follows from this, not from the wording. */
enum class InsightTone { POSITIVE, NEUTRAL, WARNING }

data class Insight(
    val title: String,
    val description: String,
    val tone: InsightTone = InsightTone.NEUTRAL
)
