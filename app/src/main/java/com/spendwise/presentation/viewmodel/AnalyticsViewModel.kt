package com.spendwise.presentation.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.domain.model.AnalyticsPeriod
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.DateRange
import com.spendwise.domain.model.ExpenseReport
import com.spendwise.domain.model.PeriodType
import com.spendwise.domain.model.ReportFormat
import com.spendwise.domain.model.SpendingNarrative
import com.spendwise.domain.repository.AnalyticsRepository
import com.spendwise.domain.usecase.AnalyticsSnapshot
import com.spendwise.domain.usecase.GetSpendingNarrativeUseCase
import com.spendwise.domain.usecase.NarrativeOutcome
import com.spendwise.domain.usecase.GetSpendingSummaryUseCase
import com.spendwise.util.NarrativePreferenceStore
import com.spendwise.util.ReportExporter
import com.spendwise.util.SaveResult
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the analytics screen from one snapshot per period.
 *
 * The previous version collected every expense ever recorded and aggregated in
 * Kotlin on each emission. That put the cost of the screen in proportion to the
 * size of the history rather than the number of figures on it, and left each
 * chart free to pick its own span — six months of bars beside an all-time
 * category split, with nothing on screen admitting the mismatch.
 */
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val getSpendingSummaryUseCase: GetSpendingSummaryUseCase,
    private val getSpendingNarrativeUseCase: GetSpendingNarrativeUseCase,
    private val analyticsRepository: AnalyticsRepository,
    private val reportExporter: ReportExporter,
    narrativePreferences: NarrativePreferenceStore
) : ViewModel() {

    private val period = MutableStateFlow(AnalyticsPeriod.thisMonth())
    private val currencyOverride = MutableStateFlow<Currency?>(null)

    /** Written summaries already paid for, keyed by the window they describe. */
    private val narratives = mutableMapOf<DateRange, SpendingNarrative>()

    val summariesEnabled: StateFlow<Boolean> = narrativePreferences.enabled

    private val _uiState = MutableStateFlow(AnalyticsUiState())
    val uiState: StateFlow<AnalyticsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // changes() re-emits on any write to expenses or incomes, so adding
            // or importing a transaction refreshes the figures behind the user.
            combine(period, currencyOverride, analyticsRepository.changes()) { selected, currency, _ ->
                selected to currency
            }
                // Settled first. A sync writes a great many rows in quick
                // succession and every one re-emits, so without this the whole
                // set of aggregates was started and abandoned once per row —
                // hundreds of times over a large import, for one usable result.
                .debounce(SETTLE_MILLIS)
                .collectLatest { (selected, currency) -> load(selected, currency) }
        }
    }

    fun selectType(type: PeriodType) {
        if (type == PeriodType.CUSTOM) return // Needs dates; see selectCustomRange.
        // The anchor carries over, so switching Month to Week while looking at
        // March lands on a week in March rather than jumping back to today.
        period.update { AnalyticsPeriod(type = type, anchor = it.anchorDate()) }
    }

    /** Pages one period back (-1) or forward (+1). */
    fun shift(steps: Long) {
        period.update { current ->
            val next = current.shifted(steps)
            // Paging into the future only ever shows an empty chart.
            if (steps > 0 && next.startsInTheFuture()) current else next
        }
    }

    /**
     * Material's date picker reports the picked day as UTC midnight, so the
     * calendar date has to be read back in UTC. Reading it in the device zone
     * would shift the selection by a day everywhere west of Greenwich.
     */
    fun selectCustomRange(startMillis: Long, endMillis: Long) {
        val first = Instant.ofEpochMilli(startMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val last = Instant.ofEpochMilli(endMillis).atZone(ZoneOffset.UTC).toLocalDate()
        period.value = AnalyticsPeriod.custom(first, last)
    }

    fun selectCurrency(currency: Currency) {
        currencyOverride.value = currency
    }

    fun retry() {
        viewModelScope.launch { load(period.value, currencyOverride.value) }
    }

    /**
     * Writes the period on screen into encrypted app-private storage.
     *
     * The transactions are re-read rather than taken from anything the screen
     * holds, but through the same period and currency, so the rows in the file
     * always add up to the totals the user was looking at.
     */
    fun export(format: ReportFormat) {
        val snapshot = _uiState.value.snapshot ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, exportMessage = null) }

            val report = runCatching {
                ExpenseReport(
                    periodLabel = snapshot.period.displayLabel(),
                    currency = snapshot.currency,
                    generatedAt = System.currentTimeMillis(),
                    summary = snapshot.summary,
                    byCategory = snapshot.byCategory.toList().sortedByDescending { it.second },
                    topMerchants = snapshot.topMerchants,
                    transactions = analyticsRepository.transactions(snapshot.period, snapshot.currency)
                )
            }.getOrElse { error ->
                Log.w(TAG, "Could not assemble the report.", error)
                _uiState.update {
                    it.copy(isExporting = false, exportMessage = "Could not build the report")
                }
                return@launch
            }

            when (val result = withContext(Dispatchers.IO) { reportExporter.save(report, format) }) {
                is SaveResult.Saved -> {
                    // Offered for sharing straight away. The stored copy is
                    // encrypted and app-private, so without this the user has a
                    // report they cannot actually get at.
                    val shareUri = withContext(Dispatchers.IO) {
                        reportExporter.shareableCopy(result.fileName)
                    }
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            exportMessage = "Saved ${result.fileName}",
                            pendingShare = shareUri?.let { uri -> PendingShare(uri, format) }
                        )
                    }
                }
                is SaveResult.Failed -> _uiState.update {
                    it.copy(isExporting = false, exportMessage = result.message)
                }
            }
        }
    }

    /** Called once the share sheet has been shown, so it is not offered twice. */
    fun clearPendingShare() {
        _uiState.update { it.copy(pendingShare = null) }
        viewModelScope.launch(Dispatchers.IO) { reportExporter.clearSharedCopies() }
    }

    fun clearExportMessage() {
        _uiState.update { it.copy(exportMessage = null) }
    }

    /**
     * Writes a summary of the period on screen.
     *
     * Only ever from an explicit tap. Loading one automatically would bill a
     * model call for opening the screen and another for every period the user
     * pages through, which is the opposite of what a paid feature should do.
     */
    fun summarisePeriod() {
        val snapshot = _uiState.value.snapshot ?: return
        val key = snapshot.period.range()

        // Already written for this exact window — a second tap is free.
        narratives[key]?.let { cached ->
            _uiState.update { it.copy(narrative = cached, narrativeError = null) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSummarising = true, narrativeError = null) }

            when (val outcome = getSpendingNarrativeUseCase(snapshot)) {
                is NarrativeOutcome.Ready -> {
                    narratives[key] = outcome.narrative
                    _uiState.update {
                        it.copy(isSummarising = false, narrative = outcome.narrative)
                    }
                }
                NarrativeOutcome.NothingToSay -> _uiState.update {
                    it.copy(
                        isSummarising = false,
                        narrativeError = "Not enough activity in this period to summarise."
                    )
                }
                NarrativeOutcome.Disabled -> _uiState.update {
                    it.copy(
                        isSummarising = false,
                        narrativeError = "Turn on written summaries in your profile first."
                    )
                }
                is NarrativeOutcome.Failed -> _uiState.update {
                    it.copy(isSummarising = false, narrativeError = outcome.message)
                }
            }
        }
    }

    private suspend fun load(selected: AnalyticsPeriod, currency: Currency?) {
        _uiState.update { current ->
            current.copy(
                period = selected,
                isLoading = true,
                error = null,
                // Figures from the period we just left would sit under the new
                // period's heading until the reload lands. A refreshing state is
                // honest; stale numbers with a new label are not.
                snapshot = current.snapshot.takeIf { current.period == selected },
                // Same for the written summary, restored from cache when this
                // window has already been summarised once.
                narrative = narratives[selected.range()],
                narrativeError = null
            )
        }

        runCatching { getSpendingSummaryUseCase(selected, currency) }
            .onSuccess { snapshot ->
                _uiState.update { it.copy(isLoading = false, snapshot = snapshot, error = null) }
            }
            .onFailure { error ->
                Log.w(TAG, "Could not load analytics for $selected.", error)
                _uiState.update {
                    it.copy(isLoading = false, error = "Could not load analytics for this period.")
                }
            }
    }

    private fun AnalyticsPeriod.anchorDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        if (type == PeriodType.CUSTOM) {
            Instant.ofEpochMilli(range(zone).end).atZone(zone).toLocalDate()
        } else {
            anchor
        }
}

data class AnalyticsUiState(
    val period: AnalyticsPeriod = AnalyticsPeriod.thisMonth(),
    val isLoading: Boolean = true,
    val snapshot: AnalyticsSnapshot? = null,
    val error: String? = null,
    val isExporting: Boolean = false,
    /** One-shot result of the last export; cleared once shown. */
    val exportMessage: String? = null,
    /** A freshly saved report waiting to be offered to the share sheet. */
    val pendingShare: PendingShare? = null,
    val isSummarising: Boolean = false,
    val narrative: SpendingNarrative? = null,
    val narrativeError: String? = null
) {
    /** False once the next period would start in the future. */
    val canGoForward: Boolean get() = !period.shifted(1).startsInTheFuture()

    /** Loaded, but the period holds nothing to draw. */
    val isEmpty: Boolean
        get() = snapshot != null &&
            snapshot.summary.transactionCount == 0 &&
            snapshot.summary.totalIncome == 0.0

}

data class PendingShare(val uri: Uri, val format: ReportFormat)

private const val TAG = "AnalyticsViewModel"

/** Long enough to let a burst of writes finish, short enough to still feel live. */
private const val SETTLE_MILLIS = 300L
