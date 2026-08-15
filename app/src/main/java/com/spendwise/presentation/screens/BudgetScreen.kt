package com.spendwise.presentation.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.spendwise.domain.model.CategoryBudgetStatus
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.presentation.components.Amount
import com.spendwise.presentation.components.SpendWiseGreen
import com.spendwise.presentation.components.SpendWiseOrange
import com.spendwise.presentation.components.SpendWisePurple
import com.spendwise.presentation.components.SpendWiseTextMuted
import com.spendwise.presentation.components.chartColor
import com.spendwise.presentation.viewmodel.BudgetViewModel
import com.spendwise.util.CurrencyFormatter

/**
 * Monthly limits per category, and how much of each is left.
 *
 * Per category rather than one figure for everything, because a single overall
 * number cannot be acted on: knowing ₹40,000 went out last month tells you
 * nothing about what to do differently, where "₹9,000 on eating out against a
 * ₹6,000 limit" does.
 */
@Composable
fun BudgetScreen(
    onNavigateUp: () -> Unit,
    viewModel: BudgetViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<ExpenseCategory?>(null) }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateUp) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text("Budgets", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (state.statuses.isEmpty()) {
                        "Set a monthly limit per category"
                    } else {
                        val currency = state.statuses.first().currency
                        "${CurrencyFormatter.format(state.totalSpent, currency)} of " +
                            CurrencyFormatter.format(state.totalLimit, currency) + " this month"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = SpendWiseTextMuted
                )
            }
            IconButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add a budget")
            }
        }

        if (state.statuses.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No budgets yet.\n\nSet a limit on a category and this screen will " +
                        "track what is left of it, and warn you before it runs out.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpendWiseTextMuted
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.statuses, key = { "${it.category.name}|${it.currency.code}" }) { status ->
                    BudgetCard(
                        status = status,
                        onClick = { editing = status.category },
                        onRemove = { viewModel.removeBudget(status) }
                    )
                }
            }
        }
    }

    if (adding) {
        CategoryPickerSheet(
            categories = state.unbudgeted,
            onDismiss = { adding = false },
            onPick = {
                adding = false
                editing = it
            }
        )
    }

    editing?.let { category ->
        val existing = state.statuses.firstOrNull { it.category == category }
        LimitSheet(
            category = category,
            initialLimit = existing?.limit?.let { limit ->
                if (limit % 1.0 == 0.0) limit.toLong().toString() else limit.toString()
            } ?: "",
            onDismiss = { editing = null },
            onSave = { limit ->
                viewModel.setLimit(category, limit)
                editing = null
            }
        )
    }
}

@Composable
private fun BudgetCard(
    status: CategoryBudgetStatus,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(status.category.chartColor, CircleShape)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    status.category.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(12.dp))
                Amount(CurrencyFormatter.format(status.spent, status.currency))
            }

            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                // Clamped for drawing only — the figures below still say by how
                // much the limit was passed.
                progress = { status.fractionUsed.coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = status.barColor(),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (status.isOverBudget) {
                        "${CurrencyFormatter.format(-status.remaining, status.currency)} over " +
                            "the ${CurrencyFormatter.format(status.limit, status.currency)} limit"
                    } else {
                        "${CurrencyFormatter.format(status.remaining, status.currency)} left of " +
                            CurrencyFormatter.format(status.limit, status.currency)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status.isOverBudget) SpendWiseOrange else SpendWiseTextMuted,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${status.percentUsed}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = SpendWiseTextMuted
                )
            }

            TextButton(
                onClick = onRemove,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
            ) {
                Text("Remove", style = MaterialTheme.typography.labelSmall, color = SpendWiseTextMuted)
            }
        }
    }
}

/** Green while there is room, amber approaching the limit, red past it. */
@Composable
private fun CategoryBudgetStatus.barColor(): Color = when {
    isOverBudget -> Color(0xFFEF4444)
    isNearLimit -> SpendWiseOrange
    else -> SpendWiseGreen
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPickerSheet(
    categories: List<ExpenseCategory>,
    onDismiss: () -> Unit,
    onPick: (ExpenseCategory) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Which category?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (categories.isEmpty()) {
                Text(
                    "Every category already has a budget.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpendWiseTextMuted
                )
            }
            LazyColumn(
                modifier = Modifier.height(320.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(categories, key = { it.name }) { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(category) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(category.chartColor, CircleShape)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(category.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LimitSheet(
    category: ExpenseCategory,
    initialLimit: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var limit by remember(category) { mutableStateOf(initialLimit) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Monthly limit for ${category.label}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "You will be told once when you pass 80% of it, and again if you go over.",
                style = MaterialTheme.typography.bodySmall,
                color = SpendWiseTextMuted
            )
            OutlinedTextField(
                value = limit,
                onValueChange = { limit = it },
                label = { Text("Amount") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onSave(limit) }) { Text("Save") }
                TextButton(onClick = onDismiss) { Text("Cancel", color = SpendWiseTextMuted) }
            }
        }
    }
}
