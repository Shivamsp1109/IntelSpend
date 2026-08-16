package com.spendwise.presentation.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.MerchantGroup
import com.spendwise.domain.model.TransactionNature
import com.spendwise.presentation.components.Amount
import com.spendwise.presentation.components.SpendWisePurple
import com.spendwise.presentation.components.SpendWiseSoftPurple
import com.spendwise.presentation.components.SpendWiseTextMuted
import com.spendwise.presentation.components.chartColor
import com.spendwise.presentation.viewmodel.RecategoriseFilter
import com.spendwise.presentation.viewmodel.RecategoriseViewModel
import com.spendwise.util.CurrencyFormatter
import com.spendwise.util.DateUtils

/**
 * Fixes categorisation a merchant at a time.
 *
 * Exists because history cannot be reclassified retrospectively. Transactions
 * imported before the app could tell spending from a transfer are all recorded
 * as spending, and only the user knows which were which — but they know it per
 * merchant, not per row. So the unit here is the merchant, and one decision
 * settles every transaction under it.
 */
@Composable
fun RecategoriseScreen(
    onNavigateUp: () -> Unit,
    viewModel: RecategoriseViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<MerchantGroup?>(null) }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateUp) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text("Fix categories", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (state.needsAttentionCount > 0) {
                        "${state.needsAttentionCount} merchants need a category"
                    } else {
                        "Everything is categorised"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = SpendWiseTextMuted
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RecategoriseFilter.entries.forEach { option ->
                FilterChip(
                    selected = state.filter == option,
                    onClick = { viewModel.setFilter(option) },
                    label = {
                        Text(
                            if (option == RecategoriseFilter.NeedsAttention) {
                                "${option.label} (${state.needsAttentionCount})"
                            } else {
                                "${option.label} (${state.totalGroups})"
                            }
                        )
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SpendWisePurple,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        if (state.groups.isEmpty()) {
            EmptyRecategoriseState(state.filter)
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.groups, key = { "${it.merchant}|${it.category.name}|${it.nature.name}" }) { group ->
                MerchantGroupCard(group = group, onClick = { editing = group })
            }
        }
    }

    editing?.let { group ->
        CategoryAndNatureSheet(
            group = group,
            onDismiss = { editing = null },
            onApply = { category, nature ->
                viewModel.apply(group, category, nature)
                editing = null
            }
        )
    }
}

@Composable
private fun MerchantGroupCard(group: MerchantGroup, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(group.category.chartColor, CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    group.merchant,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(group.category.label)
                        // Only worth saying when it is not ordinary spending —
                        // that is the case the user is here to correct.
                        if (!group.nature.isSpending) append(" · ${group.nature.label}")
                        append(" · ${group.count} ")
                        append(if (group.count == 1) "transaction" else "transactions")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = SpendWiseTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "Latest ${DateUtils.formatDate(group.latestDate)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SpendWiseTextMuted
                )
            }
            Spacer(Modifier.width(12.dp))
            Amount(CurrencyFormatter.format(group.total, group.currency))
        }
    }
}

/**
 * Sets both at once, because they are one decision.
 *
 * A credit-card payment is not a Bills expense that happens to be excluded —
 * choosing the category without choosing the nature is what leaves a transfer
 * filed somewhere plausible and still inflating the month.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryAndNatureSheet(
    group: MerchantGroup,
    onDismiss: () -> Unit,
    onApply: (ExpenseCategory, TransactionNature) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var category by remember(group) { mutableStateOf(group.category) }
    var nature by remember(group) { mutableStateOf(group.nature) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(group.merchant, style = MaterialTheme.typography.titleMedium)
            Text(
                "${group.count} ${if (group.count == 1) "transaction" else "transactions"} · " +
                    CurrencyFormatter.format(group.total, group.currency),
                style = MaterialTheme.typography.bodySmall,
                color = SpendWiseTextMuted
            )

            Spacer(Modifier.height(18.dp))
            Text("Category", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                items(ExpenseCategory.entries) { option ->
                    SelectableRow(
                        label = option.label,
                        selected = option == category,
                        accent = option.chartColor,
                        onClick = { category = option }
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("Counts as", style = MaterialTheme.typography.labelLarge)
            Text(
                "Anything other than Spending is left out of your totals.",
                style = MaterialTheme.typography.labelSmall,
                color = SpendWiseTextMuted
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
                items(TransactionNature.entries) { option ->
                    SelectableRow(
                        label = option.label,
                        selected = option == nature,
                        accent = SpendWisePurple,
                        onClick = { nature = option }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onApply(category, nature) }) {
                    Text("Apply to all ${group.count}")
                }
            }
        }
    }
}

@Composable
private fun SelectableRow(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) SpendWiseSoftPurple else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(accent, CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun EmptyRecategoriseState(filter: RecategoriseFilter) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (filter == RecategoriseFilter.NeedsAttention) {
                "Nothing needs attention. Every merchant has a category."
            } else {
                "No transactions with a merchant name yet."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = SpendWiseTextMuted
        )
    }
}
