package com.spendwise.presentation.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.spendwise.domain.model.AnalyticsPeriod
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.Insight
import com.spendwise.domain.model.InsightTone
import com.spendwise.domain.model.LargestExpense
import com.spendwise.domain.model.PeriodType
import com.spendwise.domain.model.ReportFormat
import com.spendwise.domain.model.SpendingHeatmap
import com.spendwise.domain.model.SpendingNarrative
import com.spendwise.domain.usecase.AnalyticsSnapshot
import com.spendwise.presentation.components.Amount
import com.spendwise.presentation.components.BottomDestination
import com.spendwise.presentation.components.CategoryBreakdownList
import com.spendwise.presentation.components.CategoryComparisonList
import com.spendwise.presentation.components.CategoryDonut
import com.spendwise.presentation.components.HeaderRow
import com.spendwise.presentation.components.IncomeVsExpenseChart
import com.spendwise.presentation.components.MerchantList
import com.spendwise.presentation.components.MetricCard
import com.spendwise.presentation.components.SpendTrendChart
import com.spendwise.presentation.components.SpendingHeatmapGrid
import com.spendwise.presentation.components.WeekdaySplitChart
import com.spendwise.presentation.components.SpendWiseGreen
import com.spendwise.presentation.components.SpendWisePurple
import com.spendwise.presentation.components.SpendWiseScreen
import com.spendwise.presentation.components.SpendWiseSoftPurple
import com.spendwise.presentation.components.SpendWiseTextMuted
import com.spendwise.presentation.components.chartColor
import com.spendwise.presentation.viewmodel.AnalyticsViewModel
import com.spendwise.util.CurrencyFormatter
import com.spendwise.util.DateUtils
import com.spendwise.util.ReportExporter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun AnalyticsScreen(
    onHome: () -> Unit,
    onTransactions: () -> Unit,
    onProfile: () -> Unit,
    viewModel: AnalyticsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val summariesEnabled by viewModel.summariesEnabled.collectAsState()
    val context = LocalContext.current
    var showRangePicker by remember { mutableStateOf(false) }
    var showExportChooser by remember { mutableStateOf(false) }

    // Held across the permission round trip on older Android, where the format
    // is chosen before the system asks whether we may write at all.
    var pendingFormat by remember { mutableStateOf<ReportFormat?>(null) }

    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val format = pendingFormat
        pendingFormat = null
        when {
            format == null -> Unit
            granted -> viewModel.export(format)
            else -> viewModel.reportStorageDenied()
        }
    }

    fun startExport(format: ReportFormat) {
        if (viewModel.exportNeedsPermission()) {
            pendingFormat = format
            storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.export(format)
        }
    }

    LaunchedEffect(state.exportMessage) {
        state.exportMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            viewModel.clearExportMessage()
        }
    }

    SpendWiseScreen(
        selected = BottomDestination.Analytics,
        onHome = onHome,
        onTransactions = onTransactions,
        onAdd = {},
        onAnalytics = {},
        onProfile = onProfile,
        // This screen only reads; a floating button would sit over the charts
        // and cover the very figures it is drawn on top of.
        showAddButton = false
    ) { screenModifier ->
        Column(
            modifier = screenModifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HeaderRow(
                title = "Analytics",
                subtitle = periodLabel(state.period),
                action = {
                    // Nothing loaded means nothing to write, so the action stays
                    // inert rather than producing an empty file.
                    IconButton(
                        onClick = { showExportChooser = true },
                        enabled = state.snapshot != null && !state.isExporting
                    ) {
                        if (state.isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = SpendWisePurple
                            )
                        } else {
                            Icon(
                                Icons.Default.FileDownload,
                                contentDescription = "Export report",
                                tint = if (state.snapshot != null) SpendWisePurple else SpendWiseTextMuted
                            )
                        }
                    }
                }
            )

            PeriodTypeSelector(
                selected = state.period.type,
                onSelect = viewModel::selectType,
                onCustom = { showRangePicker = true }
            )

            PeriodNavigator(
                label = periodLabel(state.period),
                canGoForward = state.canGoForward,
                onPrevious = { viewModel.shift(-1) },
                onNext = { viewModel.shift(1) }
            )

            // Refreshing in place rather than blanking the screen — the figures
            // already shown are still for this period.
            if (state.isLoading && state.snapshot != null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = SpendWisePurple,
                    trackColor = SpendWiseSoftPurple
                )
            }

            state.error?.let { message ->
                ErrorCard(message = message, onRetry = viewModel::retry)
            }

            val snapshot = state.snapshot
            when {
                snapshot == null && state.isLoading -> LoadingBlock()
                snapshot == null -> Unit
                state.isEmpty -> EmptyPeriodCard(state.period)
                else -> AnalyticsContent(
                    snapshot = snapshot,
                    onSelectCurrency = viewModel::selectCurrency,
                    summariesEnabled = summariesEnabled,
                    isSummarising = state.isSummarising,
                    narrative = state.narrative,
                    narrativeError = state.narrativeError,
                    onSummarise = viewModel::summarisePeriod
                )
            }
        }
    }

    if (showRangePicker) {
        CustomRangeDialog(
            onDismiss = { showRangePicker = false },
            onConfirm = { start, end ->
                viewModel.selectCustomRange(start, end)
                showRangePicker = false
            }
        )
    }

    if (showExportChooser) {
        ExportChooserDialog(
            periodLabel = state.period.displayLabel(),
            onDismiss = { showExportChooser = false },
            onPick = { format ->
                showExportChooser = false
                startExport(format)
            }
        )
    }
}

/**
 * Names the period being exported. The report covers what is on screen, not the
 * whole history, and that is not obvious from an export button alone.
 */
@Composable
private fun ExportChooserDialog(
    periodLabel: String,
    onDismiss: () -> Unit,
    onPick: (ReportFormat) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export $periodLabel") },
        text = {
            Text(
                "CSV opens in a spreadsheet. PDF is a formatted report with " +
                    "totals and a category breakdown.\n\n" +
                    "Saved to Download/${ReportExporter.FOLDER} on this phone.",
                style = MaterialTheme.typography.bodySmall
            )
        },
        confirmButton = {
            TextButton(onClick = { onPick(ReportFormat.PDF) }) { Text("PDF") }
        },
        dismissButton = {
            TextButton(onClick = { onPick(ReportFormat.CSV) }) { Text("CSV") }
        }
    )
}

@Composable
private fun AnalyticsContent(
    snapshot: AnalyticsSnapshot,
    onSelectCurrency: (Currency) -> Unit,
    summariesEnabled: Boolean,
    isSummarising: Boolean,
    narrative: SpendingNarrative?,
    narrativeError: String?,
    onSummarise: () -> Unit
) {
    val currency = snapshot.currency
    val summary = snapshot.summary
    val categories = snapshot.byCategory.toList().sortedByDescending { it.second }

    if (snapshot.excludedCurrencies.isNotEmpty()) {
        CurrencyNotice(
            current = currency,
            others = snapshot.excludedCurrencies,
            onSelect = onSelectCurrency
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard(
            label = "Spent",
            value = CurrencyFormatter.format(summary.totalExpense, currency),
            caption = changeCaption(summary.expenseChangePercent),
            captionColor = changeColor(summary.expenseChangePercent),
            modifier = Modifier.weight(1f)
        )
        MetricCard(
            label = "Income",
            value = CurrencyFormatter.format(summary.totalIncome, currency),
            caption = if (summary.totalIncome > 0.0) {
                "Net ${CurrencyFormatter.format(summary.net, currency)}"
            } else {
                "None recorded"
            },
            valueColor = SpendWiseGreen,
            modifier = Modifier.weight(1f)
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard(
            label = "Saved",
            // A savings rate needs income to be a share of. Zero would read as
            // "you saved nothing", which is a claim, not an absence of data.
            value = summary.savingsRate?.let { "${(it * 100).roundToInt()}%" } ?: "—",
            caption = if (summary.savingsRate == null) "Add income to see this" else "of income",
            modifier = Modifier.weight(1f)
        )
        MetricCard(
            label = "Per day",
            value = CurrencyFormatter.format(summary.averagePerDay, currency),
            caption = "${summary.transactionCount} transactions",
            modifier = Modifier.weight(1f)
        )
    }

    if (summariesEnabled) {
        NarrativeCard(
            isLoading = isSummarising,
            narrative = narrative,
            error = narrativeError,
            onSummarise = onSummarise
        )
    }

    if (snapshot.insights.isNotEmpty()) {
        SectionCard(title = "What stands out") {
            snapshot.insights.forEachIndexed { index, insight ->
                if (index > 0) Spacer(Modifier.height(14.dp))
                InsightRow(insight)
            }
        }
    }

    SectionCard {
        SpendTrendChart(buckets = snapshot.spendOverTime, currency = currency)
    }

    // Without income the chart would just redraw the spending series under a
    // heading that promises a comparison.
    if (summary.totalIncome > 0.0) {
        SectionCard {
            IncomeVsExpenseChart(
                expense = snapshot.spendOverTime,
                income = snapshot.incomeOverTime,
                currency = currency
            )
        }
    }

    val dayCount = snapshot.period.range().dayCount

    if (snapshot.period.bucketsByDay && dayCount >= MIN_HEATMAP_DAYS) {
        val weeks = remember(snapshot.spendOverTime) {
            SpendingHeatmap.build(snapshot.spendOverTime)
        }
        if (weeks.isNotEmpty()) {
            SectionCard {
                SpendingHeatmapGrid(weeks = weeks, currency = currency)
            }
        }
    }

    // A period shorter than a week cannot show a weekly rhythm — several days
    // would have no occurrence at all and their bars would read as zero spending.
    if (dayCount >= DAYS_IN_WEEK && summary.totalExpense > 0.0) {
        SectionCard {
            WeekdaySplitChart(pattern = snapshot.weekdayPattern, currency = currency)
        }
    }

    if (categories.isNotEmpty()) {
        SectionCard(title = "Where it went") {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CategoryDonut(slices = categories, currency = currency)
            }
            Spacer(Modifier.height(18.dp))
            CategoryBreakdownList(
                slices = categories,
                changes = snapshot.categoryChange,
                currency = currency
            )
        }
    }

    // Only meaningful once there is a previous period holding something; against
    // an empty one every category would simply be reported as "New".
    if (summary.previousExpense > 0.0 && snapshot.categoryComparisons.isNotEmpty()) {
        SectionCard(title = "What changed") {
            Text(
                "Against ${previousPeriodName(snapshot.period.type)}",
                style = MaterialTheme.typography.bodySmall,
                color = SpendWiseTextMuted
            )
            Spacer(Modifier.height(16.dp))
            CategoryComparisonList(
                comparisons = snapshot.categoryComparisons,
                currency = currency
            )
        }
    }

    if (snapshot.topMerchants.isNotEmpty()) {
        SectionCard(title = "Top merchants") {
            MerchantList(merchants = snapshot.topMerchants, currency = currency)
        }
    }

    if (snapshot.largestExpenses.isNotEmpty()) {
        SectionCard(title = "Biggest single spends") {
            snapshot.largestExpenses.forEach { expense ->
                LargestExpenseRow(expense = expense, currency = currency)
            }
        }
    }
}

@Composable
private fun PeriodTypeSelector(
    selected: PeriodType,
    onSelect: (PeriodType) -> Unit,
    onCustom: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PeriodType.entries.forEach { type ->
            FilterChip(
                selected = selected == type,
                onClick = { if (type == PeriodType.CUSTOM) onCustom() else onSelect(type) },
                label = { Text(type.label) },
                shape = RoundedCornerShape(14.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = SpendWisePurple,
                    selectedLabelColor = Color.White
                )
            )
        }
    }
}

@Composable
private fun PeriodNavigator(
    label: String,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous period", tint = SpendWisePurple)
        }
        Text(label, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onNext, enabled = canGoForward) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Next period",
                // Greyed rather than hidden, so the control does not jump about
                // as the user pages back and forth.
                tint = if (canGoForward) SpendWisePurple else SpendWiseTextMuted.copy(alpha = 0.35f)
            )
        }
    }
}

/**
 * Analytics report on one currency at a time, because adding ₹ and $ into a
 * single total would be silently wrong and the app has no exchange rates. This
 * says which currency is on screen and offers the others, rather than quietly
 * under-reporting.
 */
@Composable
private fun CurrencyNotice(
    current: Currency,
    others: List<Currency>,
    onSelect: (Currency) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7E6)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Showing ${current.code} only. You also have spending in " +
                    others.joinToString { it.code } + ".",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF7A5B00)
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(current) + others).forEach { currency ->
                    FilterChip(
                        selected = currency == current,
                        onClick = { onSelect(currency) },
                        label = { Text("${currency.symbol} ${currency.code}") },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SpendWisePurple,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }
        }
    }
}

/**
 * The written summary, behind an explicit button.
 *
 * Nothing loads on its own. Each summary is a paid model call, so generating one
 * for a screen the user merely scrolled past — or for every period they page
 * through — would spend their money without them asking.
 */
@Composable
private fun NarrativeCard(
    isLoading: Boolean,
    narrative: SpendingNarrative?,
    error: String?,
    onSummarise: () -> Unit
) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("In words", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (narrative == null) "Written for you, on request" else "Written summary",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpendWiseTextMuted
                )
            }
            when {
                isLoading -> CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = SpendWisePurple
                )
                narrative == null -> TextButton(onClick = onSummarise) { Text("Summarise") }
                else -> TextButton(onClick = onSummarise) { Text("Refresh") }
            }
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8C1D2A))
        }

        narrative?.let { written ->
            Spacer(Modifier.height(14.dp))
            if (written.headline.isNotBlank()) {
                Text(
                    written.headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(written.body, style = MaterialTheme.typography.bodyMedium)

            if (written.suggestions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                written.suggestions.forEach { suggestion ->
                    Row(modifier = Modifier.padding(bottom = 6.dp)) {
                        Text("·  ", style = MaterialTheme.typography.bodySmall, color = SpendWisePurple)
                        Text(
                            suggestion,
                            style = MaterialTheme.typography.bodySmall,
                            color = SpendWiseTextMuted
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                // The figures above are computed on the device; this paragraph is
                // not, and the difference is worth stating rather than leaving the
                // reader to assume both carry the same weight.
                "Written by a model from the totals on this screen.",
                style = MaterialTheme.typography.labelSmall,
                color = SpendWiseTextMuted
            )
        }
    }
}

@Composable
private fun InsightRow(insight: Insight) {
    val accent = when (insight.tone) {
        InsightTone.POSITIVE -> SpendWiseGreen
        InsightTone.WARNING -> Color(0xFFE04F5F)
        InsightTone.NEUTRAL -> SpendWisePurple
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        // A colour bar rather than an icon: the tone is a property of the
        // finding, and there is no icon that means "spending rose" unambiguously.
        Box(
            Modifier
                .width(3.dp)
                .height(38.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                insight.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent
            )
            Text(
                insight.description,
                style = MaterialTheme.typography.bodySmall,
                color = SpendWiseTextMuted
            )
        }
    }
}

@Composable
private fun LargestExpenseRow(expense: LargestExpense, currency: Currency) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(expense.category.chartColor, CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        // Takes the slack so the amount beside it is never squeezed to nothing.
        Column(Modifier.weight(1f)) {
            Text(
                expense.merchant?.takeIf { it.isNotBlank() } ?: expense.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                DateUtils.formatDate(expense.date),
                style = MaterialTheme.typography.labelSmall,
                color = SpendWiseTextMuted
            )
        }
        Spacer(Modifier.width(12.dp))
        Amount(CurrencyFormatter.format(expense.amount, currency))
    }
}

@Composable
private fun EmptyPeriodCard(period: AnalyticsPeriod) {
    SectionCard {
        Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "No transactions recorded for ${periodLabel(period)}. " +
                "Add one, or step back to an earlier period.",
            style = MaterialTheme.typography.bodySmall,
            color = SpendWiseTextMuted
        )
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFDECEE)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8C1D2A))
            TextButton(onClick = onRetry) { Text("Retry", color = Color(0xFF8C1D2A)) }
        }
    }
}

@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = SpendWisePurple)
    }
}

@Composable
private fun SectionCard(title: String? = null, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            title?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(14.dp))
            }
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeDialog(onDismiss: () -> Unit, onConfirm: (Long, Long) -> Unit) {
    val pickerState = rememberDateRangePickerState()
    val start = pickerState.selectedStartDateMillis
    val end = pickerState.selectedEndDateMillis

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { if (start != null && end != null) onConfirm(start, end) },
                enabled = start != null && end != null
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) {
        DateRangePicker(state = pickerState, showModeToggle = false, modifier = Modifier.weight(1f))
    }
}

private val PeriodType.label: String
    get() = when (this) {
        PeriodType.DAY -> "Day"
        PeriodType.WEEK -> "Week"
        PeriodType.MONTH -> "Month"
        PeriodType.YEAR -> "Year"
        PeriodType.CUSTOM -> "Custom"
    }

/**
 * The screen's heading for a period. "Today" only ever appears here — a report
 * saved to a file uses [AnalyticsPeriod.displayLabel] and names the actual date,
 * because "Today" in a document opened next week means nothing.
 */
private fun periodLabel(period: AnalyticsPeriod, zone: ZoneId = ZoneId.systemDefault()): String {
    if (period.type == PeriodType.DAY) {
        val day = Instant.ofEpochMilli(period.range(zone).start).atZone(zone).toLocalDate()
        if (day == LocalDate.now(zone)) return "Today"
    }
    return period.displayLabel(zone)
}

private fun changeCaption(changePercent: Double?): String = when {
    changePercent == null -> "No prior period"
    changePercent > 0 -> "▲ ${(changePercent * 100).roundToInt()}% vs before"
    changePercent < 0 -> "▼ ${(abs(changePercent) * 100).roundToInt()}% vs before"
    else -> "Level with before"
}

private fun changeColor(changePercent: Double?): Color = when {
    changePercent == null || changePercent == 0.0 -> SpendWiseTextMuted
    // Spending more is the unwelcome direction, so a rise reads red.
    changePercent > 0 -> Color(0xFFE04F5F)
    else -> SpendWiseGreen
}

private fun previousPeriodName(type: PeriodType): String = when (type) {
    PeriodType.DAY -> "the day before"
    PeriodType.WEEK -> "last week"
    PeriodType.MONTH -> "last month"
    PeriodType.YEAR -> "last year"
    PeriodType.CUSTOM -> "the period before"
}

/** Below a fortnight a calendar grid holds one or two rows and shows no pattern. */
private const val MIN_HEATMAP_DAYS = 14

private const val DAYS_IN_WEEK = 7
