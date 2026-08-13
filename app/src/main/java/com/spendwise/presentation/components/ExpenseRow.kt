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
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Chair
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Wifi
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

/**
 * Icon and colour per category.
 *
 * The colour comes from the shared chart palette so a category looks the same
 * in a list row as in the analytics donut — two palettes would drift and the
 * same category would read as two different things on two screens.
 */
private fun ExpenseCategory.visuals(): Pair<ImageVector, Color> = when (this) {
    ExpenseCategory.FoodDining -> Icons.Default.Fastfood to chartColor
    ExpenseCategory.Groceries -> Icons.Default.LocalGroceryStore to chartColor
    ExpenseCategory.Transport -> Icons.Default.DirectionsBus to chartColor
    ExpenseCategory.Fuel -> Icons.Default.LocalGasStation to chartColor
    ExpenseCategory.Travel -> Icons.Default.Flight to chartColor
    ExpenseCategory.Shopping -> Icons.Default.ShoppingBag to chartColor
    ExpenseCategory.RentHousing -> Icons.Default.Home to chartColor
    ExpenseCategory.Utilities -> Icons.Default.Bolt to chartColor
    ExpenseCategory.MobileInternet -> Icons.Default.Wifi to chartColor
    ExpenseCategory.Subscriptions -> Icons.Default.Autorenew to chartColor
    ExpenseCategory.Entertainment -> Icons.Default.Movie to chartColor
    ExpenseCategory.HealthMedical -> Icons.Default.LocalHospital to chartColor
    ExpenseCategory.Insurance -> Icons.Default.Shield to chartColor
    ExpenseCategory.Education -> Icons.Default.School to chartColor
    ExpenseCategory.PersonalCare -> Icons.Default.ContentCut to chartColor
    ExpenseCategory.HomeHousehold -> Icons.Default.Chair to chartColor
    ExpenseCategory.GiftsDonations -> Icons.Default.CardGiftcard to chartColor
    ExpenseCategory.KidsFamily -> Icons.Default.ChildCare to chartColor
    ExpenseCategory.Pets -> Icons.Default.Pets to chartColor
    ExpenseCategory.TaxesGovernment -> Icons.Default.AccountBalance to chartColor
    ExpenseCategory.BankFees -> Icons.Default.CreditCard to chartColor
    ExpenseCategory.Other -> Icons.Default.Category to chartColor
}
