package com.spendwise.presentation.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
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
import com.spendwise.domain.model.InsurancePolicy
import com.spendwise.domain.model.InsuranceType
import com.spendwise.domain.model.PremiumCadence
import com.spendwise.presentation.components.Amount
import com.spendwise.presentation.components.SpendWiseOrange
import com.spendwise.presentation.components.SpendWisePurple
import com.spendwise.presentation.components.SpendWiseSoftPurple
import com.spendwise.presentation.components.SpendWiseTextMuted
import com.spendwise.presentation.components.SpendWiseTextPrimary
import com.spendwise.presentation.viewmodel.InsuranceViewModel
import com.spendwise.util.CurrencyFormatter
import com.spendwise.util.DateUtils

/**
 * The policies the user holds.
 *
 * The figure asked for is the payout, not the premium — a premium leaving the
 * account proves a policy exists and says nothing about what it would be worth
 * when it mattered, and the payout is the only number a gap analysis can use.
 *
 * "Is a nominee named?" is a three-way question rather than a checkbox. No
 * nominee is a real problem that makes a claim slow or contested; not knowing is
 * not a problem, it is an unanswered question, and a checkbox would silently
 * report the second as the first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsuranceScreen(
    onNavigateUp: () -> Unit,
    viewModel: InsuranceViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<InsurancePolicy?>(null) }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                    Text(
                        "Your Cover",
                        style = MaterialTheme.typography.titleLarge,
                        color = SpendWiseTextPrimary
                    )
                    Text(
                        if (state.policies.isEmpty()) {
                            "Add your policies"
                        } else {
                            "${CurrencyFormatter.format(state.lifeCover, state.currency)} of life cover"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = SpendWiseTextMuted
                    )
                }
            }

            if (state.policies.isEmpty() && state.isLoaded) {
                EmptyCover()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (state.lapsedCount > 0 || state.withoutNominee > 0) {
                        item { CoverWarnings(state.lapsedCount, state.withoutNominee) }
                    }
                    items(state.policies, key = { it.id }) { policy ->
                        PolicyCard(
                            policy = policy,
                            onClick = { editing = policy },
                            onDelete = { viewModel.delete(policy) }
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { adding = true },
            containerColor = SpendWisePurple,
            contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add a policy")
        }
    }

    if (adding || editing != null) {
        val target = editing
        ModalBottomSheet(
            onDismissRequest = { adding = false; editing = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            PolicyForm(
                existing = target,
                onSave = { label, type, provider, cover, premium, cadence, nominee ->
                    viewModel.save(
                        existingId = target?.id ?: 0,
                        label = label,
                        type = type,
                        provider = provider,
                        sumAssured = cover,
                        premiumAmount = premium,
                        premiumCadence = cadence,
                        policyEndDate = target?.policyEndDate,
                        nomineeSet = nominee
                    )
                    adding = false
                    editing = null
                },
                onCancel = { adding = false; editing = null }
            )
        }
    }
}

@Composable
private fun CoverWarnings(lapsed: Int, withoutNominee: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SpendWiseSoftPurple)
    ) {
        Column(Modifier.padding(16.dp)) {
            if (lapsed > 0) {
                Text(
                    "$lapsed policy(ies) have passed their end date and are not counted as cover.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpendWiseOrange
                )
            }
            if (withoutNominee > 0) {
                if (lapsed > 0) Spacer(Modifier.height(6.dp))
                Text(
                    "$withoutNominee policy(ies) have no nominee named. A claim without " +
                        "one can be slow or contested.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpendWiseOrange
                )
            }
        }
    }
}

@Composable
private fun PolicyCard(policy: InsurancePolicy, onClick: () -> Unit, onDelete: () -> Unit) {
    val lapsed = policy.hasLapsed()

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    policy.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (lapsed) SpendWiseTextMuted else SpendWiseTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(policy.type.label)
                        policy.provider?.let { append(" · ").append(it) }
                        if (lapsed) append(" · Expired")
                        else policy.policyEndDate?.let {
                            append(" · until ").append(DateUtils.formatDate(it))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (lapsed) SpendWiseOrange else SpendWiseTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Amount(
                CurrencyFormatter.format(policy.sumAssured, policy.currency),
                color = if (lapsed) SpendWiseTextMuted else SpendWiseTextPrimary
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove ${policy.label}",
                    tint = SpendWiseTextMuted
                )
            }
        }
    }
}

@Composable
private fun EmptyCover() {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "No policies recorded",
                style = MaterialTheme.typography.titleMedium,
                color = SpendWiseTextPrimary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Cover is the one thing the app cannot work out from your transactions " +
                    "— a premium leaving your account doesn't say what the policy would " +
                    "pay out. Until you add them, it won't say anything about your cover " +
                    "either way.",
                style = MaterialTheme.typography.bodyMedium,
                color = SpendWiseTextMuted
            )
        }
    }
}

@Composable
private fun PolicyForm(
    existing: InsurancePolicy?,
    onSave: (String, InsuranceType, String, String, String, PremiumCadence, Boolean?) -> Unit,
    onCancel: () -> Unit
) {
    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    var type by remember { mutableStateOf(existing?.type ?: InsuranceType.TERM_LIFE) }
    var provider by remember { mutableStateOf(existing?.provider.orEmpty()) }
    var cover by remember {
        mutableStateOf(existing?.sumAssured?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var premium by remember { mutableStateOf(existing?.premiumAmount?.toString().orEmpty()) }
    var cadence by remember { mutableStateOf(existing?.premiumCadence ?: PremiumCadence.YEARLY) }
    var nominee by remember { mutableStateOf(existing?.nomineeSet) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
    ) {
        Text(
            if (existing == null) "Add a policy" else "Edit policy",
            style = MaterialTheme.typography.titleLarge,
            color = SpendWiseTextPrimary
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("What is it?") },
            placeholder = { Text("Term plan") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = cover,
            onValueChange = { cover = it },
            label = { Text("What would it pay out?") },
            supportingText = { Text("The sum assured, not the premium.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        FieldLabel("Kind")
        ChipRow(InsuranceType.entries, type, { it.label }) { type = it }
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = provider,
            onValueChange = { provider = it },
            label = { Text("Provider (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = premium,
            onValueChange = { premium = it },
            label = { Text("Premium (optional)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        FieldLabel("How often is it paid?")
        ChipRow(PremiumCadence.entries, cadence, { it.label }) { cadence = it }
        Spacer(Modifier.height(16.dp))

        FieldLabel("Is a nominee named?")
        Text(
            "Leave as not sure if you don't know — we won't warn you about something " +
                "unconfirmed.",
            style = MaterialTheme.typography.bodySmall,
            color = SpendWiseTextMuted,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        NomineeChips(nominee) { nominee = it }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            TextButton(
                onClick = { onSave(label, type, provider, cover, premium, cadence, nominee) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Save", color = SpendWisePurple, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Three states, because "not sure" is not "no". */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NomineeChips(selected: Boolean?, onSelect: (Boolean?) -> Unit) {
    val options = listOf<Pair<Boolean?, String>>(
        true to "Yes",
        false to "No",
        null to "Not sure"
    )

    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, text) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(text, style = MaterialTheme.typography.bodySmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = SpendWiseSoftPurple,
                    selectedLabelColor = SpendWisePurple
                )
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = SpendWiseTextPrimary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(labelOf(option), style = MaterialTheme.typography.bodySmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = SpendWiseSoftPurple,
                    selectedLabelColor = SpendWisePurple
                )
            )
        }
    }
}
