package com.spendwise.presentation.components

import android.app.DatePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.presentation.viewmodel.AddExpenseViewModel
import com.spendwise.util.DateUtils
import kotlinx.coroutines.launch
import java.util.Calendar

// No separate currency list needed — we use Currency.entries directly

// ─────────────────────────────────────────────────────────────────────────────
// Entry-point: chooser sheet (Manual / Upload)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Shows a two-option chooser bottom sheet matching the IncomeBottomSheet design.
 * "Manual Entry" opens the full expense form inline; "Upload PDF/Image" is a
 * stub that will be wired to the OCR/PDF flow later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseBottomSheet(
    onDismiss: () -> Unit,
    onUpload: (String) -> Unit,
    viewModel: AddExpenseViewModel = hiltViewModel()
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var showManualForm by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White
    ) {
        if (showManualForm) {
            // ── Full manual entry form ───────────────────────────────────────
            ManualExpenseForm(
                viewModel = viewModel,
                onClose = {
                    viewModel.clearForm()
                    onDismiss()
                },
                onSaved = {
                    scope.launch {
                        sheetState.hide()
                    }.invokeOnCompletion {
                        viewModel.clearForm()
                        onDismiss()
                    }
                }
            )
        } else {
            // ── Two-option chooser ───────────────────────────────────────────
            ExpenseTypeChooser(
                onClose = onDismiss,
                onManual = { showManualForm = true },
                onUploadPdf = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onUpload("pdf") }
                },
                onUploadCsv = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onUpload("csv") }
                },
                onUploadImage = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onUpload("image") }
                },
                onScanDocument = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onUpload("scan") }
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Two-option chooser (matches IncomeBottomSheet sector-row style)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ExpenseTypeChooser(
    onClose: () -> Unit,
    onManual: () -> Unit,
    onUploadPdf: () -> Unit,
    onUploadCsv: () -> Unit,
    onUploadImage: () -> Unit,
    onScanDocument: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .padding(bottom = 24.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Add Expense",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF17102A),
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Black)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "How would you like to add this expense?",
            style = MaterialTheme.typography.bodyMedium,
            color = SpendWiseTextMuted
        )
        Spacer(Modifier.height(20.dp))

        ChooserOptionCard(
            icon = Icons.Default.Edit,
            title = "Manual Entry",
            subtitle = "Type in the details yourself",
            onClick = onManual
        )
        Spacer(Modifier.height(12.dp))
        ChooserOptionCard(
            icon = Icons.Default.Description,
            title = "Upload PDF Statement",
            subtitle = "Upload bank statement in PDF format",
            onClick = onUploadPdf
        )
        Spacer(Modifier.height(12.dp))
        ChooserOptionCard(
            icon = Icons.Default.Upload,
            title = "Upload CSV Statement",
            subtitle = "Import exported bank or card transactions",
            onClick = onUploadCsv
        )
        Spacer(Modifier.height(12.dp))
        ChooserOptionCard(
            icon = Icons.Default.Upload,
            title = "Upload Screenshot",
            subtitle = "Upload transaction screenshot from any app",
            onClick = onUploadImage
        )
        Spacer(Modifier.height(12.dp))
        ChooserOptionCard(
            icon = Icons.Default.Store,
            title = "Scan Document",
            subtitle = "Take a photo of your statement or receipt",
            onClick = onScanDocument
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ChooserOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = SpendWiseSoftPurple),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(SpendWisePurple.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = SpendWisePurple, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = Color(0xFF17102A),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    subtitle,
                    color = SpendWiseTextMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Manual entry form
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualExpenseForm(
    viewModel: AddExpenseViewModel,
    onClose: () -> Unit,
    onSaved: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var categoryExpanded by remember { mutableStateOf(false) }
    var currencyExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .padding(horizontal = 18.dp, vertical = 8.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Manual Entry",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = Color(0xFF17102A),
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Black)
            }
        }

        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── Amount card (gradient hero, same as AddExpenseScreen) ────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Box(
                    modifier = Modifier
                        .background(PurpleGradient)
                        .padding(18.dp)
                ) {
                    Column {
                        Text(
                            "Amount",
                            color = Color.White.copy(alpha = 0.72f),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Currency selector pill on the gradient card
                            CurrencyPillSelector(
                                currency = state.currency,
                                expanded = currencyExpanded,
                                onExpandedChange = { currencyExpanded = it },
                                onCurrencySelected = {
                                    viewModel.updateCurrency(it)
                                    currencyExpanded = false
                                }
                            )
                            OutlinedTextField(
                                value = state.amount,
                                onValueChange = viewModel::updateAmount,
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                placeholder = {
                                    Text(
                                        "0.00",
                                        color = Color.White.copy(alpha = 0.5f),
                                        style = MaterialTheme.typography.headlineMedium
                                    )
                                },
                                textStyle = MaterialTheme.typography.headlineMedium.copy(color = Color.White),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    cursorColor = Color.White
                                )
                            )
                        }
                    }
                }
            }

            // ── Details card ────────────────────────────────────────────────
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Title
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = viewModel::updateTitle,
                        label = { Text("Title") },
                        placeholder = { Text("Lunch with Team", color = SpendWiseTextMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = expenseFieldColors()
                    )

                    // Merchant
                    OutlinedTextField(
                        value = state.merchant,
                        onValueChange = viewModel::updateMerchant,
                        label = { Text("Merchant (optional)") },
                        placeholder = { Text("e.g. Swiggy, Amazon", color = SpendWiseTextMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Store, contentDescription = null, tint = SpendWisePurple)
                        },
                        colors = expenseFieldColors()
                    )

                    // Category dropdown
                    ExposedDropdownMenuBox(
                        expanded = categoryExpanded,
                        onExpandedChange = { categoryExpanded = !categoryExpanded }
                    ) {
                        OutlinedTextField(
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            readOnly = true,
                            value = state.category.label,
                            onValueChange = {},
                            label = { Text("Category") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Payments,
                                    contentDescription = null,
                                    tint = SpendWisePurple
                                )
                            },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded)
                            },
                            colors = expenseFieldColors()
                        )
                        ExposedDropdownMenu(
                            expanded = categoryExpanded,
                            onDismissRequest = { categoryExpanded = false }
                        ) {
                            ExpenseCategory.entries.forEach { category ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            category.label,
                                            color = Color.Black // fix: text always visible
                                        )
                                    },
                                    onClick = {
                                        viewModel.updateCategory(category)
                                        categoryExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Date — tapping opens a native DatePickerDialog
                    OutlinedTextField(
                        value = DateUtils.formatDate(state.date),
                        onValueChange = {},
                        label = { Text("Date") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                val cal = Calendar.getInstance().apply { timeInMillis = state.date }
                                DatePickerDialog(
                                    context,
                                    { _, year, month, day ->
                                        val selected = Calendar.getInstance().apply {
                                            set(year, month, day, 0, 0, 0)
                                            set(Calendar.MILLISECOND, 0)
                                        }
                                        viewModel.updateDate(selected.timeInMillis)
                                    },
                                    cal.get(Calendar.YEAR),
                                    cal.get(Calendar.MONTH),
                                    cal.get(Calendar.DAY_OF_MONTH)
                                ).show()
                            },
                        readOnly = true,
                        enabled = false, // disables keyboard; clickable above handles the tap
                        leadingIcon = {
                            Icon(
                                Icons.Default.CalendarMonth,
                                contentDescription = null,
                                tint = SpendWisePurple
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            // disabled but visually looks like a normal field
                            disabledTextColor = Color.Black,
                            disabledBorderColor = Color(0xFFE8E1F5),
                            disabledLeadingIconColor = SpendWisePurple,
                            disabledLabelColor = SpendWiseTextMuted
                        )
                    )
                }
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { viewModel.save(onSaved) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SpendWisePurple)
        ) {
            Text("Save Expense", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }

        Spacer(Modifier.height(12.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Currency pill selector — shown inside the gradient amount card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CurrencyPillSelector(
    currency: Currency,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCurrencySelected: (Currency) -> Unit
) {
    Box {
        Surface(
            modifier = Modifier
                .height(48.dp)
                .width(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { onExpandedChange(true) },
            color = Color.White.copy(alpha = 0.18f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    currency.code,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            Currency.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${option.code}  ${option.symbol}", color = Color.Black) },
                    onClick = { onCurrencySelected(option) }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Field color token
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun expenseFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.Black,
    unfocusedTextColor = Color.Black,
    cursorColor = SpendWisePurple,
    focusedBorderColor = SpendWisePurple.copy(alpha = 0.5f),
    unfocusedBorderColor = Color(0xFFE8E1F5),
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White,
    focusedLabelColor = SpendWisePurple,
    unfocusedLabelColor = SpendWiseTextMuted
)
