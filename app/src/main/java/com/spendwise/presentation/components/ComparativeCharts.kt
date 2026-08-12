package com.spendwise.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spendwise.domain.model.CategoryComparison
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.HeatmapWeek
import com.spendwise.domain.model.TimeBucket
import com.spendwise.domain.model.TimeBuckets
import com.spendwise.domain.model.WeekdayPattern
import com.spendwise.util.CurrencyFormatter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private val ExpenseColor = SpendWisePurple
private val IncomeColor = SpendWiseGreen

/**
 * Income against spending across the period.
 *
 * Two shapes for two questions. Over a handful of buckets — a year of months, a
 * week of days — paired bars show which bucket was heavy. Over a month of days
 * they cannot: income is lumpy (one salary credit against thirty days of
 * spending), so paired bars would be one green spike beside twenty-nine empty
 * slots. Running totals answer the question that actually matters there —
 * whether spending has overtaken what came in, and when.
 */
@Composable
fun IncomeVsExpenseChart(
    expense: List<TimeBucket>,
    income: List<TimeBucket>,
    currency: Currency,
    modifier: Modifier = Modifier
) {
    val count = minOf(expense.size, income.size)
    val asRunningTotal = count > MAX_PAIRED_BUCKETS

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (asRunningTotal) "Running total" else "Income vs spending",
                style = MaterialTheme.typography.titleMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendDot(IncomeColor, "In")
                Spacer(Modifier.width(12.dp))
                LegendDot(ExpenseColor, "Out")
            }
        }

        if (count == 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Nothing to compare in this period",
                style = MaterialTheme.typography.bodySmall,
                color = SpendWiseTextMuted
            )
            return@Column
        }

        val expenseSeries = expense.take(count).map { it.total }
        val incomeSeries = income.take(count).map { it.total }

        if (asRunningTotal) {
            RunningTotalChart(
                expense = expenseSeries.runningTotals(),
                income = incomeSeries.runningTotals(),
                keys = expense.take(count).map { it.key }
            )
        } else {
            PairedBarChart(
                expense = expenseSeries,
                income = incomeSeries,
                keys = expense.take(count).map { it.key }
            )
        }

        Spacer(Modifier.height(10.dp))
        val net = incomeSeries.sum() - expenseSeries.sum()
        Text(
            text = if (net >= 0) {
                "Kept ${CurrencyFormatter.format(net, currency)} of what came in"
            } else {
                "Spent ${CurrencyFormatter.format(abs(net), currency)} more than came in"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (net >= 0) IncomeColor else Color(0xFFE04F5F)
        )
    }
}

@Composable
private fun PairedBarChart(expense: List<Double>, income: List<Double>, keys: List<String>) {
    val max = maxOf(expense.maxOrNull() ?: 0.0, income.maxOrNull() ?: 0.0)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = SpendWiseTextMuted)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .padding(top = 14.dp)
    ) {
        if (expense.isEmpty() || max <= 0.0) return@Canvas

        val labelBand = 16.dp.toPx()
        val plotHeight = size.height - labelBand
        val slot = size.width / expense.size
        val barWidth = (slot * 0.3f).coerceIn(2.dp.toPx(), 14.dp.toPx())
        val corner = CornerRadius(barWidth / 3f)

        expense.indices.forEach { index ->
            val centre = index * slot + slot / 2f
            // Income sits left of centre and spending right, so the pair reads as
            // one unit rather than two unrelated series.
            drawPairBar(income[index], max, plotHeight, centre - barWidth - 1.dp.toPx(), barWidth, corner, IncomeColor)
            drawPairBar(expense[index], max, plotHeight, centre + 1.dp.toPx(), barWidth, corner, ExpenseColor)

            val measured = textMeasurer.measure(TimeBuckets.axisLabel(keys[index]), labelStyle)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(
                    x = (centre - measured.size.width / 2f)
                        .coerceIn(0f, (size.width - measured.size.width).coerceAtLeast(0f)),
                    y = plotHeight + 3.dp.toPx()
                )
            )
        }
    }
}

@Composable
private fun RunningTotalChart(expense: List<Double>, income: List<Double>, keys: List<String>) {
    // Running totals only ever climb, so the last point is the highest.
    val max = maxOf(expense.lastOrNull() ?: 0.0, income.lastOrNull() ?: 0.0)
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = SpendWiseTextMuted)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .padding(top = 14.dp)
    ) {
        if (expense.size < 2 || max <= 0.0) return@Canvas

        val labelBand = 16.dp.toPx()
        val plotHeight = size.height - labelBand
        val step = size.width / (expense.size - 1)

        fun pathFor(series: List<Double>): Path = Path().apply {
            series.forEachIndexed { index, value ->
                val x = index * step
                val y = plotHeight - (value / max).toFloat() * plotHeight
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }

        listOf(income to IncomeColor, expense to ExpenseColor).forEach { (series, color) ->
            val path = pathFor(series)
            // A soft fill under the line makes the gap between the two series
            // readable at a glance; the stroke alone leaves it ambiguous.
            val filled = Path().apply {
                addPath(path)
                lineTo(size.width, plotHeight)
                lineTo(0f, plotHeight)
                close()
            }
            drawPath(filled, color = color.copy(alpha = 0.12f))
            drawPath(path, color = color, style = Stroke(width = 2.5.dp.toPx()))
        }

        val stride = ceil(keys.size / MAX_AXIS_LABELS.toFloat()).toInt().coerceAtLeast(1)
        keys.forEachIndexed { index, key ->
            if (index % stride != 0) return@forEachIndexed
            val measured = textMeasurer.measure(TimeBuckets.axisLabel(key), labelStyle)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(
                    x = (index * step - measured.size.width / 2f)
                        .coerceIn(0f, (size.width - measured.size.width).coerceAtLeast(0f)),
                    y = plotHeight + 3.dp.toPx()
                )
            )
        }
    }
}

/**
 * Spending by day of the week, averaged over how often each day came round.
 *
 * Averages rather than totals because a month with five Mondays would otherwise
 * hand Monday a quarter more spending for nothing but the calendar.
 */
@Composable
fun WeekdaySplitChart(
    pattern: WeekdayPattern,
    currency: Currency,
    modifier: Modifier = Modifier
) {
    val max = pattern.days.maxOfOrNull { it.average } ?: 0.0

    Column(modifier = modifier.fillMaxWidth()) {
        Text("Day of the week", style = MaterialTheme.typography.titleMedium)
        Text(
            "Average spend per day",
            style = MaterialTheme.typography.bodySmall,
            color = SpendWiseTextMuted
        )
        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            pattern.days.forEach { day ->
                val fraction = if (max <= 0.0) 0f else (day.average / max).toFloat()
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((96f * fraction).coerceAtLeast(if (day.average > 0) 3f else 0f).dp)
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(if (day.isWeekend) IncomeColor else ExpenseColor)
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        day.dayOfWeek.getDisplayName(
                            java.time.format.TextStyle.SHORT,
                            Locale.getDefault()
                        ).take(3),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (day.isWeekend) IncomeColor else SpendWiseTextMuted,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        WeekendVerdict(pattern, currency)
    }
}

@Composable
private fun WeekendVerdict(pattern: WeekdayPattern, currency: Currency) {
    val uplift = pattern.weekendUplift
    val busiest = pattern.busiestDay

    Column {
        if (busiest != null) {
            Text(
                "${busiest.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())}" +
                    " is your heaviest day, averaging " +
                    CurrencyFormatter.format(busiest.average, currency),
                style = MaterialTheme.typography.bodySmall
            )
        }
        // Null when there is no weekday spending to compare against — saying
        // "weekends are 0% heavier" would be inventing a comparison.
        uplift?.let {
            val percent = (abs(it) * 100).roundToInt()
            Text(
                text = when {
                    percent < 5 -> "Weekends and weekdays run about level"
                    it > 0 -> "Weekend days run $percent% heavier than weekdays"
                    else -> "Weekend days run $percent% lighter than weekdays"
                },
                style = MaterialTheme.typography.bodySmall,
                color = SpendWiseTextMuted
            )
        }
    }
}

/**
 * A month laid out as a calendar, shaded by how much was spent each day.
 *
 * The trend chart shows how much and when; this shows the pattern — whether the
 * heavy days cluster at the start of the month, or land every Friday.
 */
@Composable
fun SpendingHeatmapGrid(
    weeks: List<HeatmapWeek>,
    currency: Currency,
    modifier: Modifier = Modifier
) {
    val max = weeks.flatMap { it.days }.filterNotNull().maxOfOrNull { it.total } ?: 0.0
    var selected by remember(weeks) { mutableStateOf<LocalDate?>(null) }
    val selectedDay = weeks.flatMap { it.days }.filterNotNull().firstOrNull { it.date == selected }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Daily pattern", style = MaterialTheme.typography.titleMedium)
            selectedDay?.let {
                Text(
                    "${it.date.format(heatmapDayFormat)} · ${CurrencyFormatter.format(it.total, currency)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ExpenseColor
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            WEEKDAY_HEADERS.forEach { day ->
                Text(
                    text = day.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = SpendWiseTextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        weeks.forEach { week ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                week.days.forEach { day ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(7.dp))
                            .background(heatColor(day?.total ?: 0.0, max, present = day != null))
                            .then(
                                if (day == null) {
                                    Modifier
                                } else {
                                    Modifier.clickable { selected = if (selected == day.date) null else day.date }
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        day?.let {
                            Text(
                                it.date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                // Dark text washes out on the deepest shades.
                                color = if (intensityOf(it.total, max) >= 3) Color.White else SpendWiseTextMuted
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Less", style = MaterialTheme.typography.labelSmall, color = SpendWiseTextMuted)
            Spacer(Modifier.width(6.dp))
            (0..4).forEach { level ->
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(shadeFor(level))
                )
                Spacer(Modifier.width(4.dp))
            }
            Spacer(Modifier.width(2.dp))
            Text("More", style = MaterialTheme.typography.labelSmall, color = SpendWiseTextMuted)
        }
    }
}

/**
 * Categories ranked by how far they moved, not by how much they hold.
 *
 * The biggest category is usually the same one every month, so ranking by size
 * says nothing new. Ranking by movement puts what changed at the top.
 */
@Composable
fun CategoryComparisonList(
    comparisons: List<CategoryComparison>,
    currency: Currency,
    modifier: Modifier = Modifier
) {
    val max = comparisons.maxOfOrNull { maxOf(it.current, it.previous) } ?: 0.0

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        comparisons.forEach { comparison ->
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(comparison.category.chartColor, CircleShape)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(comparison.category.label, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        text = movementLabel(comparison, currency),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                            comparison.change > 0 -> Color(0xFFE04F5F)
                            comparison.change < 0 -> IncomeColor
                            else -> SpendWiseTextMuted
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                ComparisonBar(
                    label = "Now",
                    amount = comparison.current,
                    max = max,
                    color = comparison.category.chartColor,
                    currency = currency
                )
                Spacer(Modifier.height(4.dp))
                ComparisonBar(
                    label = "Before",
                    amount = comparison.previous,
                    max = max,
                    color = comparison.category.chartColor.copy(alpha = 0.35f),
                    currency = currency
                )
            }
        }
    }
}

@Composable
private fun ComparisonBar(
    label: String,
    amount: Double,
    max: Double,
    color: Color,
    currency: Currency
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = SpendWiseTextMuted,
            modifier = Modifier.width(46.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(SpendWiseSoftPurple)
        ) {
            val fraction = if (max <= 0.0) 0f else (amount / max).toFloat()
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(color)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            CurrencyFormatter.format(amount, currency),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(84.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = SpendWiseTextMuted)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPairBar(
    value: Double,
    max: Double,
    plotHeight: Float,
    left: Float,
    barWidth: Float,
    corner: CornerRadius,
    color: Color
) {
    if (value <= 0.0) return
    val height = ((value / max).toFloat() * plotHeight).coerceAtLeast(2.dp.toPx())
    drawRoundRect(
        color = color,
        topLeft = Offset(left, plotHeight - height),
        size = Size(barWidth, height),
        cornerRadius = corner
    )
}

private fun movementLabel(comparison: CategoryComparison, currency: Currency): String = when {
    comparison.isNew -> "New"
    comparison.isDropped -> "Stopped"
    comparison.change == 0.0 -> "No change"
    else -> {
        val arrow = if (comparison.change > 0) "▲" else "▼"
        val amount = CurrencyFormatter.format(abs(comparison.change), currency)
        val percent = comparison.changePercent?.let { " (${(abs(it) * 100).roundToInt()}%)" } ?: ""
        "$arrow $amount$percent"
    }
}

/** Running sum, so the series shows where spending had reached by each bucket. */
private fun List<Double>.runningTotals(): List<Double> {
    var total = 0.0
    return map { value ->
        total += value
        total
    }
}

private fun intensityOf(total: Double, max: Double): Int = when {
    total <= 0.0 || max <= 0.0 -> 0
    else -> ceil(total / max * 4).toInt().coerceIn(1, 4)
}

private fun heatColor(total: Double, max: Double, present: Boolean): Color =
    if (!present) Color.Transparent else shadeFor(intensityOf(total, max))

/**
 * Five steps rather than a continuous ramp — neighbouring shades on a smooth
 * gradient are indistinguishable, so the extra precision reads as noise.
 */
private fun shadeFor(level: Int): Color = when (level) {
    0 -> SpendWiseSoftPurple
    1 -> SpendWisePurple.copy(alpha = 0.25f)
    2 -> SpendWisePurple.copy(alpha = 0.45f)
    3 -> SpendWisePurple.copy(alpha = 0.7f)
    else -> SpendWisePurple
}

private val WEEKDAY_HEADERS = listOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
    DayOfWeek.SATURDAY,
    DayOfWeek.SUNDAY
)

private val heatmapDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/** Beyond this, paired bars get too thin to compare and running totals read better. */
private const val MAX_PAIRED_BUCKETS = 14

private const val MAX_AXIS_LABELS = 8
