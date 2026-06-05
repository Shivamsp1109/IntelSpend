package com.spendwise.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.spendwise.presentation.components.ExpenseRow
import com.spendwise.presentation.components.MetricCard
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("SpendWise", style = MaterialTheme.typography.headlineMedium)
                OutlinedButton(onClick = onProfile) { Text("Profile") }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("Total Expense", CurrencyFormatter.format(state.totalExpense), Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("This Month", CurrencyFormatter.format(state.monthExpense), Modifier.weight(1f))
                    MetricCard("Today", CurrencyFormatter.format(state.todayExpense), Modifier.weight(1f))
                }
            }
        }
        item {
            Column {
                Text("Monthly Budget", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { state.budgetStatus.usagePercent.coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.budgetStatus.isNearLimit) {
                    Text(
                        "You have exceeded 90% of your budget.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onAddExpense, modifier = Modifier.weight(1f)) { Text("Add Expense") }
                OutlinedButton(onClick = onViewExpenses, modifier = Modifier.weight(1f)) { Text("Transactions") }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onAnalytics, modifier = Modifier.fillMaxWidth()) { Text("Analytics") }
        }
        item {
            Text("Recent Transactions", style = MaterialTheme.typography.titleLarge)
        }
        items(state.recentTransactions, key = { it.id }) { expense ->
            ExpenseRow(expense)
        }
        if (state.insights.isNotEmpty()) {
            item { Text("Smart Insights", style = MaterialTheme.typography.titleLarge) }
            items(state.insights) { insight ->
                Column {
                    Text(insight.title, style = MaterialTheme.typography.titleMedium)
                    Text(insight.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
