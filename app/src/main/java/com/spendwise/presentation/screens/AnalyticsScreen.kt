package com.spendwise.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.spendwise.presentation.components.BarChart
import com.spendwise.presentation.components.BottomDestination
import com.spendwise.presentation.components.DistributionList
import com.spendwise.presentation.components.HeaderRow
import com.spendwise.presentation.components.SpendWisePurple
import com.spendwise.presentation.components.SpendWiseScreen
import com.spendwise.presentation.viewmodel.AnalyticsViewModel

@Composable
fun AnalyticsScreen(
    onHome: () -> Unit,
    onTransactions: () -> Unit,
    onAddExpense: () -> Unit,
    onProfile: () -> Unit,
    viewModel: AnalyticsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    SpendWiseScreen(
        selected = BottomDestination.Analytics,
        onHome = onHome,
        onTransactions = onTransactions,
        onAdd = onAddExpense,
        onAnalytics = {},
        onProfile = onProfile
    ) { screenModifier ->
        Column(
            modifier = screenModifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HeaderRow(title = "Analytics", subtitle = "May 2025")
            ChartCard {
                BarChart(
                    title = "Expense Overview",
                    data = state.monthlySpending,
                    color = SpendWisePurple
                )
            }
            ChartCard {
                DistributionList(
                    title = "Category Breakdown",
                    data = state.categoryDistribution
                        .map { it.key.label to it.value }
                        .sortedByDescending { it.second }
                )
            }
            ChartCard {
                BarChart(
                    title = "Weekly Trend",
                    data = state.weeklyTrend.toList().sortedBy { it.first },
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun ChartCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            content()
        }
    }
}
