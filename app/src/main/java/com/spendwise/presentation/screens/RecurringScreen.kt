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
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.MatchBasis
import com.spendwise.domain.model.RecurringCadence
import com.spendwise.domain.model.RecurringCandidate
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.model.RecurringSource
import com.spendwise.domain.model.RecurringStatus
import com.spendwise.domain.model.RecurringType
import com.spendwise.domain.model.TransactionNature
import com.spendwise.domain.model.monthlyEquivalent
import com.spendwise.presentation.components.Amount
import com.spendwise.presentation.components.SpendWisePurple
import com.spendwise.presentation.components.SpendWiseTextMuted
import com.spendwise.presentation.components.chartColor
import com.spendwise.presentation.viewmodel.RecurringDraft
import com.spendwise.presentation.viewmodel.RecurringFilter
import com.spendwise.presentation.viewmodel.RecurringViewModel
import com.spendwise.util.CurrencyFormatter
import com.spendwise.util.DateUtils
import kotlin.math.roundToInt

/**
 * Shows the repeating payments the app has found, and the ones being tracked.
 *
 * Nothing here is applied without being asked. A detected pattern is a guess, and
 * a guess that quietly became a tracked commitment would put a number the user
 * never agreed to into their obligations — so every candidate waits for a yes or
 * a no.
 */
@Composable
fun RecurringScreen(
    onNavigateUp: () -> Unit,
    viewModel: RecurringViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<RecurringDraft?>(null) }

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
                .padding(start = 4.dp, end = 8.dp, top = 8.dp),
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
                        state.monthlyCommitment > 0 ->
                            "About ${CurrencyFormatter.format(state.monthlyCommitment, defaultCurrency(state.tracked))} a month committed"
                        state.candidates.isNotEmpty() ->
                            "${state.candidates.size} possible ${plural(state.candidates.size, "payment")} found"
                        else -> "Nothing tracked yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = SpendWiseTextMuted
                )
            }
            IconButton(onClick = { editing = RecurringDraft() }) {
                Icon(Icons.Default.Add, contentDescription = "Add a recurring payment")
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
                    else -> state.entriesFor(option).size
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

        if (state.filter == RecurringFilter.Detected) {
            if (state.candidates.isEmpty()) {
                // Says what a pattern actually needs, rather than "import more".
                // Someone who already has months of data and sees nothing needs
                // to know which condition their history fails, not to be told to
                // do the thing they have already done.
                EmptyRecurringState(
                    if (state.isLoaded) {
                        "Nothing found yet.\n\n" +
                            "A payment shows up here once there are three of them to the " +
                            "same place, spaced about evenly — weekly, monthly, and so on — " +
                            "for a similar amount, with the most recent one fairly recent.\n\n" +
                            "Yearly and quarterly ones need only two."
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
        } else {
            val entries = state.entriesFor(state.filter)
            if (entries.isEmpty()) {
                EmptyRecurringState(
                    when (state.filter) {
                        RecurringFilter.Active -> "Nothing active. Accept a found payment, or add one."
                        RecurringFilter.Paused -> "Nothing paused."
                        else -> "Nothing ended."
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        TrackedCard(
                            entry = entry,
                            onEdit = { editing = RecurringDraft.from(entry) },
                            onStatus = { viewModel.setStatus(entry, it) },
                            onDelete = { viewModel.delete(entry) }
                        )
                    }
                }
            }
        }
    }

    editing?.let { draft ->
        RecurringEditorSheet(
            draft = draft,
            onDismiss = { editing = null },
            onSave = {
                viewModel.save(it)
                editing = null
            }
        )
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
private fun TrackedCard(
    entry: RecurringEntry,
    onEdit: () -> Unit,
    onStatus: (RecurringStatus) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    // Both figures, always. The actual charge is what leaves the
                    // account; the monthly equivalent is what it costs to carry —
                    // showing only one of them misleads for every non-monthly
                    // cadence.
                    if (entry.cadence != RecurringCadence.MONTHLY) {
                        Text(
                            "≈ ${CurrencyFormatter.format(entry.monthlyEquivalent, entry.currency)} a month",
                            style = MaterialTheme.typography.labelSmall,
                            color = SpendWiseTextMuted
                        )
                    }
                    entry.nextDueDate?.takeIf { entry.isLive }?.let { due ->
                        Text(
                            "Next ${DateUtils.formatDate(due)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = SpendWiseTextMuted
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Amount(CurrencyFormatter.format(entry.amount, entry.currency))
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (entry.status) {
                    RecurringStatus.ACTIVE -> {
                        TextButton(onClick = { onStatus(RecurringStatus.PAUSED) }) { Text("Pause") }
                        TextButton(onClick = { onStatus(RecurringStatus.ENDED) }) { Text("Ended") }
                    }
                    RecurringStatus.PAUSED -> {
                        TextButton(onClick = { onStatus(RecurringStatus.ACTIVE) }) { Text("Resume") }
                        TextButton(onClick = { onStatus(RecurringStatus.ENDED) }) { Text("Ended") }
                    }
                    RecurringStatus.ENDED -> {
                        TextButton(onClick = { onStatus(RecurringStatus.ACTIVE) }) { Text("Reactivate") }
                        TextButton(onClick = onDelete) {
                            Text("Delete", color = SpendWiseTextMuted)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Adds or edits a commitment by hand.
 *
 * Nature and category are asked for here rather than guessed, because they are
 * what decides whether this counts as spending at all — an EMI entered as
 * ordinary spending would distort every total it touches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecurringEditorSheet(
    draft: RecurringDraft,
    onDismiss: () -> Unit,
    onSave: (RecurringDraft) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var working by remember(draft) { mutableStateOf(draft) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (draft.id == null) "Add a recurring payment" else "Edit ${draft.title}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            OutlinedTextField(
                value = working.title,
                onValueChange = { working = working.copy(title = it) },
                label = { Text("Name") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = working.amount,
                onValueChange = { working = working.copy(amount = it) },
                label = { Text("Amount") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            )

            ChipRow("How often", RecurringCadence.entries, working.cadence, { it.label }) {
                working = working.copy(cadence = it)
            }
            ChipRow("Amount", RecurringType.entries, working.type, { it.label }) {
                working = working.copy(type = it)
            }
            ChipRow("Counts as", TransactionNature.entries, working.nature, { it.label }) {
                working = working.copy(nature = it)
            }
            ChipRow("Category", ExpenseCategory.entries, working.category, { it.label }) {
                working = working.copy(category = it)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onSave(working) }) { Text("Save") }
                TextButton(onClick = onDismiss) { Text("Cancel", color = SpendWiseTextMuted) }
            }
        }
    }
}

@Composable
private fun <T> ChipRow(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = SpendWiseTextMuted)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(labelOf(option)) },
                    shape = RoundedCornerShape(14.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SpendWisePurple,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }
    }
}

/**
 * Says why this is being suggested, in the user's terms.
 *
 * A confidence figure on its own means nothing to anyone. "Six payments, about
 * the same each time" is the same information and can actually be checked against
 * what they remember.
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
    // Says plainly what tied these together, because it changes how much the
    // user should trust it. Matched on the sum alone, the payments may simply
    // have cost the same — only they can tell.
    if (candidate.basis == MatchBasis.AMOUNT) {
        append(" · matched by amount, the names differ")
    }
    if (candidate.confidence < 0.6) append(" · worth checking")
    append(" · ${(candidate.confidence * 100).roundToInt()}% sure")
}

private fun plural(count: Int, word: String): String = if (count == 1) word else "${word}s"

/** The commitments' own currency, so the header total is not silently labelled wrong. */
private fun defaultCurrency(entries: List<RecurringEntry>) =
    entries.firstOrNull()?.currency ?: com.spendwise.domain.model.Currency.INR

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
