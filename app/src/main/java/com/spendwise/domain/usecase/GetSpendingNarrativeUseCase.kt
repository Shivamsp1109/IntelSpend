package com.spendwise.domain.usecase

import com.spendwise.data.remote.ExtractionDataSource
import com.spendwise.data.remote.NamedAmount
import com.spendwise.data.remote.NarrativeRequest
import com.spendwise.data.remote.NarrativeResult
import com.spendwise.domain.model.SpendingNarrative
import com.spendwise.util.NarrativePreferenceStore
import javax.inject.Inject

/**
 * Asks the backend to describe a period in plain English.
 *
 * Every call costs money, so this never runs on its own — the caller triggers
 * it from an explicit user action, and the toggle check below is a second gate
 * rather than the only one.
 *
 * The request carries the figures the snapshot already holds and nothing else.
 * Individual transactions stay on the device: a summary is written from totals,
 * and sending the rows that produced them would be handing over more than the
 * job requires.
 */
class GetSpendingNarrativeUseCase @Inject constructor(
    private val extractionDataSource: ExtractionDataSource,
    private val narrativePreferences: NarrativePreferenceStore
) {
    suspend operator fun invoke(snapshot: AnalyticsSnapshot): NarrativeOutcome {
        if (!narrativePreferences.enabled.value) {
            return NarrativeOutcome.Disabled
        }
        if (snapshot.summary.totalExpense <= 0.0) {
            return NarrativeOutcome.NothingToSay
        }

        return when (val result = extractionDataSource.narrate(narrativeRequestFor(snapshot))) {
            is NarrativeResult.Success -> {
                val narrative = SpendingNarrative(
                    headline = result.narrative.headline,
                    body = result.narrative.narrative,
                    suggestions = result.narrative.suggestions
                )
                // A model that returned an empty body has nothing to show, and a
                // blank card reads as a bug rather than an absence.
                if (narrative.body.isBlank()) NarrativeOutcome.NothingToSay
                else NarrativeOutcome.Ready(narrative)
            }
            is NarrativeResult.QuotaExceeded -> NarrativeOutcome.Failed(result.message)
            is NarrativeResult.Failed -> NarrativeOutcome.Failed(result.message)
        }
    }

}

/**
 * Everything that leaves the device for a summary.
 *
 * A separate function so the boundary can be inspected on its own: what this
 * returns is exactly what travels, and nothing reads the snapshot downstream.
 */
internal fun narrativeRequestFor(snapshot: AnalyticsSnapshot) = NarrativeRequest(
    periodLabel = snapshot.period.displayLabel(),
    currency = snapshot.currency.code,
    totalExpense = snapshot.summary.totalExpense,
    totalIncome = snapshot.summary.totalIncome,
    previousExpense = snapshot.summary.previousExpense,
    averagePerDay = snapshot.summary.averagePerDay,
    transactionCount = snapshot.summary.transactionCount,
    topCategories = snapshot.byCategory.entries
        .sortedByDescending { it.value }
        .take(MAX_NARRATIVE_ITEMS)
        .map { NamedAmount(name = it.key.label, amount = it.value) },
    topMerchants = snapshot.topMerchants
        .take(MAX_NARRATIVE_ITEMS)
        .map { NamedAmount(name = it.name, amount = it.total, count = it.transactionCount) },
    // Passing the rule-based findings keeps the model describing conclusions the
    // app already stands behind, rather than drawing its own from the totals and
    // possibly contradicting the cards above it.
    highlights = snapshot.insights.map { "${it.title}: ${it.description}" }
)

/** Matches the server's cap; more than this is padding the prompt. */
private const val MAX_NARRATIVE_ITEMS = 5

sealed class NarrativeOutcome {
    data class Ready(val narrative: SpendingNarrative) : NarrativeOutcome()
    /** The toggle is off, so no call was made. */
    data object Disabled : NarrativeOutcome()
    data object NothingToSay : NarrativeOutcome()
    data class Failed(val message: String) : NarrativeOutcome()
}
