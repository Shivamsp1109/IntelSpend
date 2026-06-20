package com.spendwise.presentation.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.spendwise.presentation.components.ExpenseRow
import com.spendwise.presentation.components.BottomDestination
import com.spendwise.presentation.components.HeaderRow
import com.spendwise.presentation.components.PurpleGradient
import com.spendwise.presentation.components.SpendWiseGreen
import com.spendwise.presentation.components.SpendWiseOrange
import com.spendwise.presentation.components.SpendWisePurple
import com.spendwise.presentation.components.SpendWiseScreen
import com.spendwise.presentation.components.SpendWiseSoftPurple
import com.spendwise.presentation.components.SpendWiseTextMuted
import com.spendwise.presentation.viewmodel.HomeViewModel
import com.spendwise.util.CurrencyFormatter

@Composable
fun HomeScreen(
    onAddExpense: () -> Unit,
    onViewExpenses: () -> Unit,
    onAnalytics: () -> Unit,
    onProfile: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    SpendWiseScreen(
        selected = BottomDestination.Home,
        onHome = {},
        onTransactions = onViewExpenses,
        onAdd = onAddExpense,
        onAnalytics = onAnalytics,
        onProfile = onProfile
    ) {
        LazyColumn(
            modifier = it
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                HeaderRow(
                    title = "Hello, Shivam",
                    subtitle = "Here's your overview",
                    action = {
                        IconButton(onClick = onProfile) {
                            Icon(Icons.Default.Notifications, contentDescription = "Notifications", tint = SpendWisePurple)
                        }
                    }
                )
            }
            item {
                BalanceCard(
                    total = CurrencyFormatter.format(state.totalExpense),
                    syncText = "${state.networkSyncStatus.label} - ${state.networkSyncStatus.pendingSyncCount} pending"
                )
            }
            item {
                Text("Quick Summary", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SummaryTile(
                        label = "Income",
                        value = CurrencyFormatter.format(40_060.0),
                        helper = "+8.8%",
                        iconTint = SpendWiseGreen,
                        icon = Icons.Default.TrendingUp,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryTile(
                        label = "Expense",
                        value = CurrencyFormatter.format(state.monthExpense),
                        helper = "-4.5%",
                        iconTint = SpendWiseOrange,
                        icon = Icons.Default.TrendingDown,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryTile(
                        label = "Savings",
                        value = CurrencyFormatter.format((40_060.0 - state.monthExpense).coerceAtLeast(0.0)),
                        helper = "+10.5%",
                        iconTint = SpendWisePurple,
                        icon = Icons.Default.Savings,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Budget Progress", style = MaterialTheme.typography.titleMedium)
                            Text("70%", color = SpendWisePurple, style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { state.budgetStatus.usagePercent.coerceIn(0.0, 1.0).toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                            color = SpendWisePurple,
                            trackColor = SpendWiseSoftPurple
                        )
                        if (state.budgetStatus.isNearLimit) {
                            Spacer(Modifier.height(8.dp))
                            Text("You have exceeded 90% of your budget.", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Recent Transactions", style = MaterialTheme.typography.titleMedium)
                    Text("See All", color = SpendWisePurple, style = MaterialTheme.typography.labelLarge)
                }
            }
            items(state.recentTransactions, key = { it.id }) { expense ->
                ExpenseRow(expense)
            }
            if (state.insights.isNotEmpty()) {
                item { Text("Smart Insights", style = MaterialTheme.typography.titleMedium) }
                items(state.insights) { insight ->
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(insight.title, style = MaterialTheme.typography.titleMedium)
                            Text(insight.description, color = SpendWiseTextMuted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BalanceCard(total: String, syncText: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Box(
            modifier = Modifier
                .background(PurpleGradient)
                .padding(18.dp)
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawArc(
                    color = Color.White.copy(alpha = 0.28f),
                    startAngle = 210f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.62f, size.height * 0.12f),
                    size = androidx.compose.ui.geometry.Size(size.width * 0.32f, size.height * 0.55f),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            Column {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(Color.White.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        Icon(Icons.Default.Wallet, contentDescription = null, tint = Color.White)
                    }
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text("Total Balance", color = Color.White.copy(alpha = 0.76f), style = MaterialTheme.typography.labelMedium)
                        Text(total, color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(syncText, color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SummaryTile(
    label: String,
    value: String,
    helper: String,
    iconTint: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(iconTint.copy(alpha = 0.13f), CircleShape),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(label, color = SpendWiseTextMuted, style = MaterialTheme.typography.labelMedium)
            Text(value, color = Color(0xFF17102A), style = MaterialTheme.typography.labelLarge)
            Text(helper, color = iconTint, style = MaterialTheme.typography.labelSmall)
        }
    }
}
