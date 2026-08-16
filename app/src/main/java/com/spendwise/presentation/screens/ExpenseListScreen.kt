package com.spendwise.presentation.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.presentation.components.AddExpenseBottomSheet
import com.spendwise.presentation.components.BottomDestination
import com.spendwise.presentation.components.ExpenseRow
import com.spendwise.presentation.components.HeaderRow
import com.spendwise.presentation.components.SpendWisePurple
import com.spendwise.presentation.components.SpendWiseScreen
import com.spendwise.presentation.components.SpendWiseTextPrimary
import com.spendwise.presentation.components.SpendWiseTextMuted
import com.spendwise.presentation.viewmodel.ExpenseListViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseListScreen(
    onHome: () -> Unit,
    onAddExpense: () -> Unit,   // kept for Upload path / future deep link
    onImport: (Uri, String, String) -> Unit,
    onAnalytics: () -> Unit,
    onProfile: () -> Unit,
    viewModel: ExpenseListViewModel = hiltViewModel()
) {
    val filterState by viewModel.filterState.collectAsState()
    val expenses = viewModel.pagedExpenses.collectAsLazyPagingItems()
    var filterExpanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Expense?>(null) }
    var deleting by remember { mutableStateOf<Expense?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImport(uri, "document.pdf", "pdf")
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri, "transactions.csv", "csv")
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImport(uri, "screenshot.jpg", "screenshot")
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraUri != null) onImport(cameraUri!!, "scan.jpg", "scan")
    }

    SpendWiseScreen(
        selected = BottomDestination.Transactions,
        onHome = onHome,
        onTransactions = {},
        onAdd = { showAddSheet = true },
        onAnalytics = onAnalytics,
        onProfile = onProfile
    ) { screenModifier ->
        Column(
            modifier = screenModifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeaderRow(title = "Transactions", titleColor = Color.White)
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = filterState.query,
                    onValueChange = { q -> viewModel.updateFilterState { it.copy(query = q) } },
                    placeholder = { Text("Search transactions", color = SpendWiseTextMuted) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = SpendWiseTextMuted)
                    },
                    shape = RoundedCornerShape(18.dp),
                    colors = searchFieldColors()
                )
                IconButton(
                    onClick = { filterExpanded = true },
                    modifier = Modifier.background(Color.White, CircleShape)
                ) {
                    Icon(Icons.Default.FilterList, contentDescription = "Filter", tint = SpendWisePurple)
                }
            }
            if (filterExpanded) {
                val distinctCategories by viewModel.distinctCategories.collectAsState()
                val distinctTitles by viewModel.distinctTitles.collectAsState()
                val distinctMerchants by viewModel.distinctMerchants.collectAsState()
                val distinctCurrencies by viewModel.distinctCurrencies.collectAsState()

                com.spendwise.presentation.components.ExpenseSortFilterSheet(
                    filterState = filterState,
                    onFilterStateChange = { viewModel.updateFilterState { _ -> it } },
                    onDismiss = { filterExpanded = false },
                    distinctCategories = distinctCategories,
                    distinctTitles = distinctTitles,
                    distinctMerchants = distinctMerchants,
                    distinctCurrencies = distinctCurrencies
                )
            }
            if (filterState.categories.isNotEmpty() || filterState.merchants.isNotEmpty() || filterState.titles.isNotEmpty() || filterState.currencies.isNotEmpty() || filterState.startDate != null || filterState.endDate != null) {
                val filters = (filterState.categories + filterState.merchants + filterState.titles + filterState.currencies).joinToString(", ")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Filtered by $filters", color = SpendWisePurple, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { 
                        viewModel.updateFilterState { it.copy(categories = emptySet(), titles = emptySet(), merchants = emptySet(), currencies = emptySet(), startDate = null, endDate = null) }
                    }) {
                        Text("Clear", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (expenses.loadState.refresh is LoadState.Loading) {
                    item { Text("Loading expenses...") }
                }
                items(
                    count = expenses.itemCount,
                    key = expenses.itemKey { it.id }
                ) { index ->
                    expenses[index]?.let { expense ->
                        var menuExpanded by remember { mutableStateOf(false) }
                        ExpenseRow(
                            expense = expense,
                            trailing = {
                                Box {
                                    IconButton(onClick = { menuExpanded = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = SpendWiseTextMuted)
                                    }
                                    androidx.compose.material3.DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Edit") },
                                            onClick = {
                                                menuExpanded = false
                                                editing = expense
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete") },
                                            onClick = {
                                                menuExpanded = false
                                                deleting = expense
                                            }
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
                when (val append = expenses.loadState.append) {
                    is LoadState.Error -> item {
                        Text("Could not load more expenses: ${append.error.message}")
                    }
                    LoadState.Loading -> item { Text("Loading more...") }
                    is LoadState.NotLoading -> Unit
                }
            }
        }
    }

    // ── Add expense bottom sheet ─────────────────────────────────────────────
    if (showAddSheet) {
        AddExpenseBottomSheet(
            onDismiss = { showAddSheet = false },
            onUpload = { type ->
                showAddSheet = false
                when (type) {
                    "pdf" -> pdfLauncher.launch("application/pdf")
                    "csv" -> csvLauncher.launch(SPREADSHEET_MIME_TYPES)
                    "image" -> imageLauncher.launch("image/*")
                    "scan" -> {
                        val file = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        cameraUri = uri
                        cameraLauncher.launch(uri)
                    }
                    else -> onAddExpense()
                }
            }
        )
    }

    // ── Edit dialog ──────────────────────────────────────────────────────────
    editing?.let { expense ->
        EditExpenseDialog(
            expense = expense,
            onDismiss = { editing = null },
            onSave = {
                viewModel.update(it)
                editing = null
            }
        )
    }

    // ── Delete confirmation ──────────────────────────────────────────────────
    deleting?.let { expense ->
        DeleteConfirmationDialog(
            expense = expense,
            onDismiss = { deleting = null },
            onConfirm = {
                viewModel.delete(expense)
                deleting = null
            }
        )
    }
}

@Composable
private fun searchFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent,
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White,
    // The container is white but the text colour was left to the theme, which
    // picks one for the screen's tinted background — so what the user typed came
    // out pale grey on white and was hard to read back.
    focusedTextColor = SpendWiseTextPrimary,
    unfocusedTextColor = SpendWiseTextPrimary,
    cursorColor = SpendWisePurple
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditExpenseDialog(
    expense: Expense,
    onDismiss: () -> Unit,
    onSave: (Expense) -> Unit
) {
    var title by remember(expense.id) { mutableStateOf(expense.title) }
    var amount by remember(expense.id) { mutableStateOf(expense.amount.toString()) }
    var merchant by remember(expense.id) { mutableStateOf(expense.merchant ?: "") }
    var category by remember(expense.id) { mutableStateOf(expense.category) }
    var currency by remember(expense.id) { mutableStateOf(expense.currency) }
    var date by remember(expense.id) { mutableStateOf(expense.date) }

    var catExpanded by remember { mutableStateOf(false) }
    var curExpanded by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = date)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Expense") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("Amount") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = merchant,
                        onValueChange = { merchant = it },
                        label = { Text("Merchant") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { catExpanded = it }) {
                        OutlinedTextField(
                            value = category.label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Category") },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                        )
                        ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
                            ExpenseCategory.entries.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat.label) },
                                    onClick = {
                                        category = cat
                                        catExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                item {
                    ExposedDropdownMenuBox(expanded = curExpanded, onExpandedChange = { curExpanded = it }) {
                        OutlinedTextField(
                            value = currency.code,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Currency") },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = curExpanded) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                        )
                        ExposedDropdownMenu(expanded = curExpanded, onDismissRequest = { curExpanded = false }) {
                            com.spendwise.domain.model.Currency.entries.forEach { cur ->
                                DropdownMenuItem(
                                    text = { Text(cur.code) },
                                    onClick = {
                                        currency = cur
                                        curExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                item {
                    androidx.compose.material3.OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(com.spendwise.util.DateUtils.formatDate(date))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = amount.toDoubleOrNull()
                    if (title.isNotBlank() && parsed != null && parsed > 0) {
                        onSave(expense.copy(
                            title = title.trim(), 
                            amount = parsed,
                            merchant = merchant.trim().takeIf { it.isNotBlank() },
                            category = category,
                            currency = currency,
                            date = date
                        ))
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    if (showDatePicker) {
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { date = it }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) {
            androidx.compose.material3.DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun DeleteConfirmationDialog(
    expense: Expense,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Do you want to delete this transaction?") },
        text = {
            ExpenseRow(expense = expense)
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Yes") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("No") }
        }
    )
}
