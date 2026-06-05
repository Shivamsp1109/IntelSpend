package com.spendwise.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.spendwise.util.CurrencyFormatter

@Composable
fun BarChart(
    title: String,
    data: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        val max = data.maxOfOrNull { it.second } ?: 0.0
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .padding(top = 12.dp)
        ) {
            if (data.isEmpty() || max == 0.0) return@Canvas
            val spacing = 10.dp.toPx()
            val barWidth = (size.width - spacing * (data.size + 1)) / data.size
            data.forEachIndexed { index, item ->
                val height = (item.second / max).toFloat() * size.height
                val left = spacing + index * (barWidth + spacing)
                drawRect(
                    color = color,
                    topLeft = Offset(left, size.height - height),
                    size = Size(barWidth, height)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            data.take(6).forEach { Text(it.first.take(4), style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
fun DistributionList(
    title: String,
    data: List<Pair<String, Double>>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        data.forEach { (label, amount) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label)
                Text(CurrencyFormatter.format(amount))
            }
        }
    }
}
