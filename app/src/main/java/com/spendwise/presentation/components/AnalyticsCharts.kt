package com.spendwise.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.MerchantSpend
import com.spendwise.domain.model.TimeBucket
import com.spendwise.domain.model.TimeBuckets
import com.spendwise.util.CurrencyFormatter
import kotlin.math.ceil

/**
 * One colour per category, used by the donut, the ranked list and anywhere else
 * a category appears. Shared so a slice and its list row can never disagree
 * about which category the reader is looking at.
 */
val ExpenseCategory.chartColor: Color
    get() = when (this) {
        ExpenseCategory.Food -> Color(0xFFFF6B6B)
        ExpenseCategory.Travel -> Color(0xFF4D96FF)
        ExpenseCategory.Shopping -> SpendWisePurple
        ExpenseCategory.Bills -> SpendWiseOrange
        ExpenseCategory.Health -> SpendWiseGreen
        ExpenseCategory.Entertainment -> Color(0xFFD44BD8)
        ExpenseCategory.Other -> Color(0xFF9A93A8)
    }

/**
 * Spending across the period, one bar per bucket.
 *
 * Buckets arrive gap-filled, so empty days occupy their place on the axis
 * instead of closing up — otherwise spending on the 1st and the 30th draws as
 * two neighbouring bars and reads as two consecutive days.
 *
 * Bars are tappable because at a month's width there is no room to label every
 * one, and an unlabelled bar you cannot interrogate is decoration.
 */
@Composable
fun SpendTrendChart(
    buckets: List<TimeBucket>,
    currency: Currency,
    modifier: Modifier = Modifier,
    barColor: Color = SpendWisePurple
) {
    val max = buckets.maxOfOrNull { it.total } ?: 0.0
    val peakIndex = buckets.indexOfFirst { it.total == max }.takeIf { max > 0.0 && it >= 0 }
    var selectedIndex by remember(buckets) { mutableStateOf<Int?>(null) }
    val focused = selectedIndex ?: peakIndex

    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = SpendWiseTextMuted)
    val average = if (buckets.isEmpty()) 0.0 else buckets.sumOf { it.total } / buckets.size

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text("Spending", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = when {
                        focused == null -> "Nothing recorded in this period"
                        selectedIndex != null -> TimeBuckets.fullLabel(buckets[focused].key)
                        else -> "Highest: ${TimeBuckets.fullLabel(buckets[focused].key)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = SpendWiseTextMuted
                )
            }
            if (focused != null) {
                Text(
                    CurrencyFormatter.format(buckets[focused].total, currency),
                    style = MaterialTheme.typography.titleMedium,
                    color = barColor
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .padding(top = 14.dp)
                .pointerInput(buckets) {
                    detectTapGestures { tap ->
                        if (buckets.isEmpty()) return@detectTapGestures
                        val index = (tap.x / size.width * buckets.size).toInt()
                        selectedIndex = index.coerceIn(0, buckets.lastIndex)
                    }
                }
        ) {
            if (buckets.isEmpty()) return@Canvas

            val labelBand = 16.dp.toPx()
            val plotHeight = size.height - labelBand
            val slot = size.width / buckets.size
            val barWidth = (slot * 0.6f).coerceIn(2.dp.toPx(), 26.dp.toPx())
            val corner = CornerRadius(barWidth / 3f)

            // A reference line for the daily average, so a bar can be read as
            // "an ordinary day" or "not one" without doing arithmetic.
            if (average > 0.0 && max > 0.0) {
                val y = plotHeight - (average / max).toFloat() * plotHeight
                drawLine(
                    color = SpendWiseTextMuted.copy(alpha = 0.45f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(6.dp.toPx(), 6.dp.toPx())
                    )
                )
            }

            val labelStride = ceil(buckets.size / MAX_AXIS_LABELS.toFloat()).toInt().coerceAtLeast(1)

            buckets.forEachIndexed { index, bucket ->
                val left = index * slot + (slot - barWidth) / 2f
                val ratio = if (max <= 0.0) 0f else (bucket.total / max).toFloat()
                // A spent-but-tiny day still deserves a visible mark; rounding it
                // to nothing would read as a day with no spending at all.
                val height = if (bucket.total > 0.0) {
                    (ratio * plotHeight).coerceAtLeast(3.dp.toPx())
                } else {
                    0f
                }

                drawRoundRect(
                    color = if (index == focused) barColor else barColor.copy(alpha = 0.28f),
                    topLeft = Offset(left, plotHeight - height),
                    size = Size(barWidth, height),
                    cornerRadius = corner
                )

                val isLabelled = index % labelStride == 0 || index == focused
                if (isLabelled) {
                    val label = TimeBuckets.axisLabel(bucket.key)
                    val measured = textMeasurer.measure(
                        text = label,
                        style = if (index == focused) {
                            labelStyle.copy(color = barColor, fontWeight = FontWeight.Bold)
                        } else {
                            labelStyle
                        }
                    )
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(
                            // Keep the first and last labels inside the canvas
                            // instead of letting them hang off the edge.
                            x = (left + barWidth / 2f - measured.size.width / 2f)
                                .coerceIn(0f, (size.width - measured.size.width).coerceAtLeast(0f)),
                            y = plotHeight + 3.dp.toPx()
                        )
                    )
                }
            }
        }
    }
}

/**
 * Category split as a ring, with the period total in the middle.
 *
 * A ring rather than a pie so the centre can carry the total — the number most
 * people are looking for — instead of leaving it to a caption elsewhere.
 */
@Composable
fun CategoryDonut(
    slices: List<Pair<ExpenseCategory, Double>>,
    currency: Currency,
    modifier: Modifier = Modifier
) {
    val total = slices.sumOf { it.second }

    Box(modifier = modifier.size(180.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val ring = 26.dp.toPx()
            // The stroke straddles the arc path, so the circle has to be inset by
            // half the ring width or the outer edge clips against the bounds.
            val diameter = minOf(size.width, size.height) - ring
            val topLeft = Offset(
                x = (size.width - diameter) / 2f,
                y = (size.height - diameter) / 2f
            )
            val arcSize = Size(diameter, diameter)

            if (total <= 0.0) {
                drawArc(
                    color = SpendWiseSoftPurple,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = ring)
                )
                return@Canvas
            }

            // A hairline gap keeps adjacent slices distinguishable when their
            // colours are close; a single slice gets none, or the ring would
            // show a nick for no reason.
            val gap = if (slices.size > 1) 1.6f else 0f
            var start = -90f
            slices.forEach { (category, amount) ->
                val sweep = (amount / total).toFloat() * 360f
                drawArc(
                    color = category.chartColor,
                    startAngle = start + gap / 2f,
                    sweepAngle = (sweep - gap).coerceAtLeast(0.6f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = ring)
                )
                start += sweep
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Total", style = MaterialTheme.typography.labelSmall, color = SpendWiseTextMuted)
            Text(
                CurrencyFormatter.format(total, currency),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Categories ranked by spend, each with its share of the period and how it moved
 * against the period before. The movement is the part that carries information —
 * "₹8,000 on food" is a fact, "₹8,000, up ₹3,100" is a finding.
 */
@Composable
fun CategoryBreakdownList(
    slices: List<Pair<ExpenseCategory, Double>>,
    changes: Map<ExpenseCategory, Double>,
    currency: Currency,
    modifier: Modifier = Modifier
) {
    val total = slices.sumOf { it.second }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        slices.forEach { (category, amount) ->
            val share = if (total <= 0.0) 0f else (amount / total).toFloat()
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(category.chartColor, CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        category.label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        "  ${(share * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = SpendWiseTextMuted
                    )
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Amount(CurrencyFormatter.format(amount, currency))
                        DeltaLabel(changes[category], currency)
                    }
                }
                Spacer(Modifier.height(6.dp))
                ShareBar(fraction = share, color = category.chartColor)
            }
        }
    }
}

/**
 * An amount that keeps its own width.
 *
 * Every row here pairs a name that can be arbitrarily long with a figure that
 * cannot be abbreviated. Laying that out with `SpaceBetween` and no weight is
 * what produced the earlier damage: a long bank narration claimed the whole row,
 * the amount was measured at zero width, and "₹12,897.17" wrapped to one
 * character per line — a single row several hundred dp tall. The name column
 * takes the slack; the figure never wraps.
 */
@Composable
fun Amount(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false
    )
}

@Composable
fun MerchantList(
    merchants: List<MerchantSpend>,
    currency: Currency,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        merchants.forEachIndexed { index, merchant ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(SpendWiseSoftPurple, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = SpendWisePurple
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        merchant.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        // Small-and-frequent spending hides inside a total —
                        // forty ₹200 orders look like one ₹8,000 line.
                        "${merchant.transactionCount}× · " +
                            "${CurrencyFormatter.format(merchant.averageTransaction, currency)} avg",
                        style = MaterialTheme.typography.labelSmall,
                        color = SpendWiseTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(12.dp))
                Amount(CurrencyFormatter.format(merchant.total, currency))
            }
        }
    }
}

/** Rise or fall against the previous period. Silent when there is no movement. */
@Composable
fun DeltaLabel(change: Double?, currency: Currency) {
    if (change == null || change == 0.0) return
    val rising = change > 0
    Text(
        text = (if (rising) "▲ " else "▼ ") +
            CurrencyFormatter.format(kotlin.math.abs(change), currency),
        style = MaterialTheme.typography.labelSmall,
        // Spending more is the unwelcome direction here, so red reads up.
        color = if (rising) Color(0xFFE04F5F) else SpendWiseGreen
    )
}

@Composable
private fun ShareBar(fraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.15f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
    }
}

/**
 * About as many labels as fit across a phone without overlapping. Sized so a
 * seven-day week labels every bar rather than every other one.
 */
private const val MAX_AXIS_LABELS = 8
