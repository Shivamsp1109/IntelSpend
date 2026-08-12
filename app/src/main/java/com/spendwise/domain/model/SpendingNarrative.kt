package com.spendwise.domain.model

/**
 * A written summary of one period.
 *
 * Model-generated, so it is presented as commentary alongside the figures
 * rather than as another figure. The charts remain the source of truth.
 */
data class SpendingNarrative(
    val headline: String,
    val body: String,
    val suggestions: List<String> = emptyList()
)
