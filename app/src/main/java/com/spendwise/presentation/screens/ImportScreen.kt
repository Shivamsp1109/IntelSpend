package com.spendwise.presentation.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.spendwise.data.ingestion.model.RawTransaction
import com.spendwise.data.ingestion.model.TransactionType
import com.spendwise.data.ingestion.model.ReviewSeverity
import com.spendwise.presentation.components.CategoryBadge
import com.spendwise.presentation.viewmodel.ImportState
import com.spendwise.presentation.viewmodel.ImportViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    fileUri: Uri?,
    fileName: String?,
    sourceType: String?,
    onNavigateUp: () -> Unit,
    onNavigateToTransactions: () -> Unit,
    viewModel: ImportViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(fileUri, fileName, sourceType) {
        if (fileUri != null && fileName != null && uiState.state == ImportState.IDLE) {
            viewModel.processFile(fileUri, fileName, sourceType ?: "image")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Transactions") }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (uiState.state) {
                ImportState.IDLE, ImportState.PROCESSING -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Processing $fileName...")
                    }
                }
                ImportState.PASSWORD_REQUIRED -> {
                    PasswordPrompt(
                        fileName = uiState.fileName,
                        rejected = uiState.passwordRejected,
                        onSubmit = viewModel::submitPassword,
                        onCancel = {
                            viewModel.cancelPassword()
                            onNavigateUp()
                        }
                    )
                }
                ImportState.REVIEW -> {
                    ReviewContent(
                        transactions = uiState.transactions,
                        warning = uiState.warning,
                        onToggleSelection = viewModel::toggleSelection,
                        onUpdateTransaction = viewModel::updateTransaction,
                        onSave = viewModel::saveSelected
                    )
                }
                ImportState.SAVING -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Saving transactions...")
                    }
                }
                ImportState.COMPLETE -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "${uiState.savedCount} transactions added successfully!",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(onClick = {
                            viewModel.deleteFileAndReset(context.cacheDir)
                            onNavigateToTransactions()
                        }) {
                            Text("View All Transactions")
                        }
                    }
                }
                ImportState.ERROR -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            uiState.errorMessage ?: "Unknown error occurred",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(onClick = onNavigateUp) {
                            Text("Go Back")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewContent(
    transactions: List<RawTransaction>,
    warning: String? = null,
    onToggleSelection: (Int) -> Unit,
    onUpdateTransaction: (Int, RawTransaction) -> Unit,
    onSave: () -> Unit
) {
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    Column(modifier = Modifier.fillMaxSize()) {
        val selectedCount = transactions.count { it.isSelected }

        // Shown when the results are degraded — most often smart extraction was
        // enabled but couldn't run. Without this the user sees a plausible-looking
        // list with no indication that the good extractor never ran.
        if (warning != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(Color(0xFFFFF4E5), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFB26A00),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = warning,
                    color = Color(0xFF8A5300),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Review Transactions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text("$selectedCount selected")
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(transactions) { index, tx ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleSelection(index) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(if (tx.isDuplicate) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f) else Color.Transparent),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = tx.isSelected,
                        onCheckedChange = { onToggleSelection(index) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tx.merchant ?: tx.title, style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CategoryBadge(
                                category = tx.category,
                                modifier = Modifier.clickable {
                                    selectedIndex = index
                                    showCategoryPicker = true
                                }
                            )
                            val severity = tx.fieldConfidence.severity()
                            if (tx.isDuplicate) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Duplicate", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                            } else if (severity != ReviewSeverity.GREEN) {
                                Spacer(modifier = Modifier.width(8.dp))
                                val (badgeText, badgeColor) = when (severity) {
                                    ReviewSeverity.RED -> "Uncertain Amount/Date" to Color.Red
                                    ReviewSeverity.YELLOW -> "Please Review Fields" to Color(0xFFF2A104)
                                    else -> "" to Color.Transparent
                                }
                                if (badgeText.isNotEmpty()) {
                                    Text(badgeText, color = badgeColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    Text(
                        text = if (tx.type == TransactionType.DEBIT) "-${tx.currency.symbol}${tx.amount}" else "+${tx.currency.symbol}${tx.amount}",
                        color = if (tx.type == TransactionType.DEBIT) MaterialTheme.colorScheme.error else Color(0xFF4CAF50),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                HorizontalDivider()
            }
        }

        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            enabled = selectedCount > 0
        ) {
            Text("Add to Expenses ($selectedCount)")
        }
    }

    if (showCategoryPicker && selectedIndex != null) {
        CategoryPickerSheet(
            sheetState = sheetState,
            onDismiss = { showCategoryPicker = false },
            onCategorySelected = { category ->
                val idx = selectedIndex!!
                val updatedTx = transactions[idx].copy(category = category)
                onUpdateTransaction(idx, updatedTx)
            }
        )
    }
}

@Composable
private fun PasswordPrompt(
    fileName: String,
    rejected: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("This PDF is password protected", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            fileName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            isError = rejected,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            supportingText = {
                if (rejected) {
                    Text("That password didn't work. Try again.")
                } else {
                    // Indian bank statements almost always use a derived password, and users
                    // rarely realise it isn't their net-banking password.
                    Text("Bank statements often use your PAN, date of birth, or account number.")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = { onSubmit(password) },
                enabled = password.isNotBlank()
            ) {
                Text("Unlock")
            }
        }
    }
}
