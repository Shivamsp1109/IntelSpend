package com.spendwise.presentation.screens

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.spendwise.domain.model.RecurringCandidate
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.model.RecurringSource
import com.spendwise.domain.model.RecurringType
import com.spendwise.presentation.components.Amount
import com.spendwise.presentation.components.SpendWisePurple
import com.spendwise.presentation.components.SpendWiseTextMuted
import com.spendwise.presentation.components.chartColor
import com.spendwise.presentation.viewmodel.RecurringFilter
import com.spendwise.presentation.viewmodel.RecurringViewModel
import com.spendwise.util.CurrencyFormatter
import com.spendwise.util.DateUtils
import kotlin.math.roundToInt

/**
 * Shows the repeating payments the app has found, and the ones being tracked.
 *
 * Nothing here is applied without being asked. A detected pattern is a guess,
 * and a guess that quietly became a tracked commitment would put a number the
 * user never agreed to into their obligations — so every candidate waits for a
 * yes or a no.
 */
@Composable
fun RecurringScreen(
    onNavigateUp: () -> Unit,
    viewModel: RecurringViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current

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
                .padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateUp) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text("Recurring payments", style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        !state.isLoaded -> "Looking through your history…"
                        state.candidates.isNotEmpty() ->
                            "${state.candidates.size} possible ${plural(state.candidates.size, "payment")} found"
                        state.tracked.isNotEmpty() -> "Tracking ${state.tracked.size}"
                        else -> "Nothing found yet"
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
            RecurringFilter.entries.forEach { option ->
                val count = when (option) {
                    RecurringFilter.Detected -> state.candidates.size
                    RecurringFilter.Tracked -> state.tracked.size
                }
                FilterChip(
                    selected = state.filter == option,
                    onClick = { viewModel.setFilter(option) },
                    label = { Text("${option.label} ($count)") },
                    shape = RoundedCornerShape(14.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SpendWisePurple,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        when (state.filter) {
            RecurringFilter.Detected ->
                if (state.candidates.isEmpty()) {
                    EmptyRecurringState(
                        if (state.isLoaded) {
                            "No repeating payments found. Import a few months of " +
                                "statements and they will show up here."
                        } else {
                            "Looking through your history…"
                        }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.candidates, key = { it.signature }) { candidate ->
                            CandidateCard(
                                candidate = candidate,
                                onConfirm = { viewModel.confirm(candidate) },
                                onDismiss = { viewModel.dismiss(candidate) }
                            )
                        }
                    }
                }

            RecurringFilter.Tracked ->
                if (state.tracked.isEmpty()) {
                    EmptyRecurringState("Nothing tracked yet. Accept a found payment to start.")
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.tracked, key = { it.id }) { entry ->
                            TrackedCard(entry = entry, onDelete = { viewModel.delete(entry) })
                        }
                    }
                }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: RecurringCandidate,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(candidate.category.chartColor, CircleShape)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        candidate.merchant,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        describe(candidate),
                        style = MaterialTheme.typography.labelSmall,
                        color = SpendWiseTextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Last paid ${DateUtils.formatDate(candidate.lastOccurrenceDate)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = SpendWiseTextMuted
                    )
                }
                Spacer(Modifier.width(12.dp))
                Amount(CurrencyFormatter.format(candidate.averageAmount, candidate.currency))
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onConfirm) { Text("Track this") }
                TextButton(onClick = onDismiss) {
                    Text("Not recurring", color = SpendWiseTextMuted)
                }
            }
        }
    }
}

@Composable
private fun TrackedCard(entry: RecurringEntry, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                    .background(entry.category.chartColor, CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(entry.cadence.label)
                        append(" · ")
                        append(entry.category.label)
                        if (!entry.nature.isSpending) append(" · ${entry.nature.label}")
                        if (entry.source == RecurringSource.DETECTED) append(" · found automatically")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = SpendWiseTextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
                    Text("Stop tracking", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(12.dp))
            Amount(CurrencyFormatter.format(entry.amount, entry.currency))
        }
    }
}

/**
 * Says why this is being suggested, in the user's terms.
 *
 * A confidence figure on its own means nothing to anyone. "Six payments, about
 * the same each time" is the same information and can actually be checked
 * against what they remember.
 */
private fun describe(candidate: RecurringCandidate): String = buildString {
    append(candidate.cadence.label.lowercase())
    append(" · ")
    append(candidate.occurrenceCount)
    append(" payments")
    append(
        if (candidate.type == RecurringType.FIXED) ", same amount each time"
        else ", amount varies"
    )
    if (candidate.confidence < 0.6) append(" · worth checking")
    append(" · ${(candidate.confidence * 100).roundToInt()}% sure")
}

private fun plural(count: Int, word: String): String = if (count == 1) word else "${word}s"

@Composable
private fun EmptyRecurringState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = SpendWiseTextMuted,
            fontWeight = FontWeight.Normal
        )
    }
}
