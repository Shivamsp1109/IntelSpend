package com.spendwise.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.util.CurrencyFormatter
import com.spendwise.util.DateUtils

@Composable
fun ExpenseRow(
    expense: Expense,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CategoryBadge(expense.category)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    expense.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Black,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    "${expense.category.label} - ${DateUtils.formatDate(expense.date)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    CurrencyFormatter.format(expense.amount),
                    style = MaterialTheme.typography.titleMedium,
                    color = SpendWisePurple
                )
                Text(DateUtils.formatDate(expense.date), style = MaterialTheme.typography.labelSmall, color = SpendWiseTextMuted)
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun CategoryBadge(category: ExpenseCategory, modifier: Modifier = Modifier) {
    val (icon, color) = category.visuals()
    Box(
        modifier = modifier
            .size(42.dp)
            .background(color.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = category.label, tint = color)
    }
}

private fun ExpenseCategory.visuals(): Pair<ImageVector, Color> = when (this) {
    ExpenseCategory.Food -> Icons.Default.Fastfood to SpendWiseOrange
    ExpenseCategory.Travel -> Icons.Default.Flight to Color(0xFF3B82F6)
    ExpenseCategory.Shopping -> Icons.Default.ShoppingBag to Color(0xFF00B875)
    ExpenseCategory.Bills -> Icons.Default.ReceiptLong to Color(0xFF8B5CF6)
    ExpenseCategory.Health -> Icons.Default.LocalHospital to Color(0xFFEF4444)
    ExpenseCategory.Entertainment -> Icons.Default.Movie to Color(0xFFE11D48)
    ExpenseCategory.Other -> Icons.Default.Category to SpendWiseTextMuted
}
