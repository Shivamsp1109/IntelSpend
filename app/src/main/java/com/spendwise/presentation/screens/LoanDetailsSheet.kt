package com.spendwise.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.spendwise.domain.model.InterestCompounding
import com.spendwise.domain.model.PrepaymentChargeType
import com.spendwise.domain.model.RateType
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.presentation.components.SpendWisePurple
import com.spendwise.presentation.components.SpendWiseSoftPurple
import com.spendwise.presentation.components.SpendWiseTextMuted
import com.spendwise.presentation.components.SpendWiseTextPrimary
import com.spendwise.presentation.viewmodel.LoanDetailsViewModel

/**
 * The terms behind an EMI, asked for one field at a time and none of them
 * required beyond the balance.
 *
 * The wording matters here. Every optional field says what answering it would
 * unlock, and every "not sure" is an explicit choice rather than an empty box —
 * because a guessed interest rate produces a confident payoff figure that reads
 * exactly like a real one, and someone could prepay a loan on the strength of
 * it. Saying "not sure" is a better outcome than a plausible invention, and the
 * form is built to make that the easy path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoanDetailsSheet(
    entry: RecurringEntry,
    onDismiss: () -> Unit,
    viewModel: LoanDetailsViewModel = hiltViewModel()
) {
    val existing by viewModel.details.collectAsState()

    LaunchedEffect(entry.id) { viewModel.load(entry.id) }

    var principal by remember(existing) {
        mutableStateOf(existing?.principalOutstanding?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var rate by remember(existing) {
        mutableStateOf(existing?.interestRate?.toString().orEmpty())
    }
    var rateType by remember(existing) { mutableStateOf(existing?.rateType ?: RateType.UNKNOWN) }
    var compounding by remember(existing) {
        mutableStateOf(existing?.compounding ?: InterestCompounding.MONTHLY)
    }
    var payment by remember(existing) {
        mutableStateOf((existing?.scheduledPayment ?: entry.amount).toString())
    }
    var installments by remember(existing) {
        mutableStateOf(existing?.remainingInstallments?.toString().orEmpty())
    }
    var chargeType by remember(existing) {
        mutableStateOf(existing?.prepaymentChargeType ?: PrepaymentChargeType.UNKNOWN)
    }
    var chargeValue by remember(existing) {
        mutableStateOf(existing?.prepaymentChargeValue?.toString().orEmpty())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            Text(
                "Loan terms",
                style = MaterialTheme.typography.titleLarge,
                color = SpendWiseTextPrimary
            )
            Text(
                "For ${entry.title}. Only the balance is needed — fill in what you " +
                    "know and leave the rest.",
                style = MaterialTheme.typography.bodySmall,
                color = SpendWiseTextMuted
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = principal,
                onValueChange = { principal = it },
                label = { Text("How much is still owed?") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = payment,
                onValueChange = { payment = it },
                label = { Text("Monthly payment") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = installments,
                onValueChange = { installments = it },
                label = { Text("Payments left (optional)") },
                supportingText = {
                    Text("Knowing this lets the app work out the total interest exactly.")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = rate,
                onValueChange = { rate = it },
                label = { Text("Interest rate % a year (optional)") },
                supportingText = {
                    Text("Leave blank if you are not sure — a guess would make every figure wrong.")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            SheetLabel("Is the rate fixed?")
            ChipRow(RateType.entries, rateType, { it.label }) { rateType = it }
            Spacer(Modifier.height(16.dp))

            SheetLabel("How does interest build up?")
            ChipRow(InterestCompounding.entries, compounding, { it.label }) { compounding = it }
            Spacer(Modifier.height(16.dp))

            SheetLabel("Charge for paying it off early?")
            Text(
                "Without this the app will not tell you what paying early would save, " +
                    "because a fee could wipe the saving out.",
                style = MaterialTheme.typography.bodySmall,
                color = SpendWiseTextMuted,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            ChipRow(PrepaymentChargeType.entries, chargeType, { it.label }) { chargeType = it }

            if (chargeType == PrepaymentChargeType.FLAT ||
                chargeType == PrepaymentChargeType.PERCENT_OF_PRINCIPAL
            ) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = chargeValue,
                    onValueChange = { chargeValue = it },
                    label = {
                        Text(
                            if (chargeType == PrepaymentChargeType.FLAT) "How much?" else "What percentage?"
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing != null) {
                    TextButton(
                        onClick = { viewModel.clear(entry.id); onDismiss() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Remove", color = SpendWiseTextMuted)
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                TextButton(
                    onClick = {
                        viewModel.save(
                            recurringId = entry.id,
                            principal = principal,
                            rate = rate,
                            rateType = rateType,
                            compounding = compounding,
                            payment = payment,
                            remainingInstallments = installments,
                            prepaymentChargeType = chargeType,
                            prepaymentChargeValue = chargeValue,
                            currency = entry.currency
                        )
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save", color = SpendWisePurple, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
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
