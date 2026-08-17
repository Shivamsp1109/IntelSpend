package com.spendwise.presentation.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.spendwise.domain.model.Asset
import com.spendwise.domain.model.AssetOwnership
import com.spendwise.domain.model.AssetType
import com.spendwise.domain.model.LiquidityClass
import com.spendwise.presentation.components.Amount
import com.spendwise.presentation.components.SpendWiseGreen
import com.spendwise.presentation.components.SpendWisePurple
import com.spendwise.presentation.components.SpendWiseSoftPurple
import com.spendwise.presentation.components.SpendWiseTextMuted
import com.spendwise.presentation.components.SpendWiseTextPrimary
import com.spendwise.presentation.viewmodel.AssetsViewModel
import com.spendwise.util.CurrencyFormatter

/**
 * What the user owns.
 *
 * The screen asks for liquidity separately from type, and that is the question
 * doing the most work here. A fixed deposit and a five-year tax-saving deposit
 * look identical on a statement and only one can be reached in an emergency —
 * inferring it from the type would quietly count locked money as a reserve, and
 * a reserve figure that is wrong fails at exactly the moment it is relied on.
 *
 * Nothing here asks for an expected return. What something is worth today is a
 * fact the user can check; what it will earn is a dated assumption about an
 * asset class, and collecting it here would let a later projection present it as
 * something they told us.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetsScreen(
    onNavigateUp: () -> Unit,
    viewModel: AssetsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Asset?>(null) }
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
                        "What You Own",
                        style = MaterialTheme.typography.titleLarge,
                        color = SpendWiseTextPrimary
                    )
                    Text(
                        if (state.assets.isEmpty()) {
                            "Add your accounts and holdings"
                        } else {
                            "${CurrencyFormatter.format(state.total, state.currency)} in total"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = SpendWiseTextMuted
                    )
                }
            }

            if (state.assets.isEmpty() && state.isLoaded) {
                EmptyAssets()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item { ReachableSummary(state.reachableNow, state.total, state.currency) }
                    items(state.assets, key = { it.id }) { asset ->
                        AssetCard(
                            asset = asset,
                            onClick = { editing = asset },
                            onDelete = { viewModel.delete(asset) }
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
            Icon(Icons.Default.Add, contentDescription = "Add a holding")
        }
    }

    if (adding || editing != null) {
        val target = editing
        ModalBottomSheet(
            onDismissRequest = { adding = false; editing = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            AssetForm(
                existing = target,
                onSave = { label, type, value, liquidity, ownership, lockIn, account ->
                    viewModel.save(
                        existingId = target?.id ?: 0,
                        label = label,
                        type = type,
                        value = value,
                        liquidity = liquidity,
                        ownership = ownership,
                        lockInUntil = lockIn,
                        accountType = account
                    )
                    adding = false
                    editing = null
                },
                onCancel = { adding = false; editing = null }
            )
        }
    }
}

/**
 * The gap between what is owned and what could be spent this week.
 *
 * Shown together on purpose. Someone with ₹20,00,000 in a provident fund and
 * ₹5,000 in the bank has a large net worth and no emergency reserve, and a
 * single total would hide the only part of that which matters in a crisis.
 */
@Composable
private fun ReachableSummary(reachable: Double, total: Double, currency: com.spendwise.domain.model.Currency) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SpendWiseSoftPurple)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Reachable quickly",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpendWiseTextPrimary
                )
                Amount(CurrencyFormatter.format(reachable, currency), color = SpendWiseGreen)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (reachable < total) {
                    "The rest is tied up in holdings that would take time to reach."
                } else {
                    "All of what you own could be reached quickly."
                },
                style = MaterialTheme.typography.bodySmall,
                color = SpendWiseTextMuted
            )
        }
    }
}

@Composable
private fun AssetCard(asset: Asset, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    asset.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = SpendWiseTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(asset.type.label)
                        append(" · ")
                        append(asset.liquidity.label)
                        if (asset.ownership != AssetOwnership.SELF) {
                            append(" · ").append(asset.ownership.label)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = SpendWiseTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Amount(
                CurrencyFormatter.format(asset.currentValue, asset.currency),
                color = SpendWiseTextPrimary
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove ${asset.label}",
                    tint = SpendWiseTextMuted
                )
            }
        }
    }
}

@Composable
private fun EmptyAssets() {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Nothing recorded yet",
                style = MaterialTheme.typography.titleMedium,
                color = SpendWiseTextPrimary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Adding your accounts, deposits and investments lets the app work " +
                    "out your net worth and how long you could manage if your income stopped.",
                style = MaterialTheme.typography.bodyMedium,
                color = SpendWiseTextMuted
            )
        }
    }
}

@Composable
private fun AssetForm(
    existing: Asset?,
    onSave: (String, AssetType, String, LiquidityClass, AssetOwnership, Long?, String?) -> Unit,
    onCancel: () -> Unit
) {
    var label by remember { mutableStateOf(existing?.label.orEmpty()) }
    var value by remember {
        mutableStateOf(existing?.currentValue?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var type by remember { mutableStateOf(existing?.type ?: AssetType.BANK_ACCOUNT) }
    var liquidity by remember { mutableStateOf(existing?.liquidity ?: LiquidityClass.LIQUID_CASH) }
    var ownership by remember { mutableStateOf(existing?.ownership ?: AssetOwnership.SELF) }
    var account by remember { mutableStateOf(existing?.accountType.orEmpty()) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
    ) {
        Text(
            if (existing == null) "Add a holding" else "Edit holding",
            style = MaterialTheme.typography.titleLarge,
            color = SpendWiseTextPrimary
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("What is it?") },
            placeholder = { Text("HDFC savings") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("What is it worth now?") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        FormLabel("Kind")
        ChipRow(AssetType.entries, type, { it.label }) { type = it }
        Spacer(Modifier.height(16.dp))

        FormLabel("How quickly could you reach it?")
        Text(
            liquidity.description,
            style = MaterialTheme.typography.bodySmall,
            color = SpendWiseTextMuted,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        ChipRow(LiquidityClass.entries, liquidity, { it.label }) { liquidity = it }
        Spacer(Modifier.height(16.dp))

        FormLabel("Whose is it?")
        ChipRow(AssetOwnership.entries, ownership, { it.label }) { ownership = it }
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = account,
            onValueChange = { account = it },
            label = { Text("Where is it held? (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            TextButton(
                onClick = {
                    onSave(label, type, value, liquidity, ownership, existing?.lockInUntil, account)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Save", color = SpendWisePurple, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = SpendWiseTextPrimary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

/** Wraps rather than scrolls: a horizontal strip hides the options off-screen. */
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
