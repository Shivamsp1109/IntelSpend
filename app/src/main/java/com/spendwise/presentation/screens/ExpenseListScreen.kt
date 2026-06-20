package com.spendwise.presentation.screens

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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.spendwise.domain.model.Expense
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.presentation.components.BottomDestination
import com.spendwise.presentation.components.ExpenseRow
import com.spendwise.presentation.components.HeaderRow
import com.spendwise.presentation.components.SpendWisePurple
import com.spendwise.presentation.components.SpendWiseScreen
import com.spendwise.presentation.components.SpendWiseTextMuted
import com.spendwise.presentation.viewmodel.ExpenseListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseListScreen(
    onHome: () -> Unit,
    onAddExpense: () -> Unit,
    onAnalytics: () -> Unit,
    onProfile: () -> Unit,
    viewModel: ExpenseListViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val expenses = viewModel.pagedExpenses.collectAsLazyPagingItems()
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Expense?>(null) }

    SpendWiseScreen(
        selected = BottomDestination.Transactions,
        onHome = onHome,
        onTransactions = {},
        onAdd = onAddExpense,
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
            HeaderRow(title = "Transactions")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::updateQuery,
                    label = { Text("Search transactions") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = SpendWiseTextMuted)
                    },
                    shape = RoundedCornerShape(18.dp),
                    colors = searchFieldColors()
                )
                IconButton(
                    onClick = { expanded = true },
                    modifier = Modifier.background(Color.White, CircleShape)
                ) {
                    Icon(Icons.Default.FilterList, contentDescription = "Filter", tint = SpendWisePurple)
                }
            }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                Box(Modifier.menuAnchor())
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("All categories") },
                        onClick = {
                            viewModel.updateCategory(null)
                            expanded = false
                        }
                    )
                    ExpenseCategory.entries.forEach { category ->
                        DropdownMenuItem(
                            text = { Text(category.label) },
                            onClick = {
                                viewModel.updateCategory(category)
                                expanded = false
                            }
                        )
                    }
                }
            }
            state.selectedCategory?.let {
                Text("Filtered by ${it.label}", color = SpendWisePurple, style = MaterialTheme.typography.labelLarge)
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
                        ExpenseRow(
                            expense = expense,
                            trailing = {
                                Row {
                                    TextButton(onClick = { editing = expense }) { Text("Edit") }
                                    TextButton(onClick = { viewModel.delete(expense) }) { Text("Delete") }
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
}

@Composable
private fun searchFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent,
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White
)

@Composable
private fun EditExpenseDialog(
    expense: Expense,
    onDismiss: () -> Unit,
    onSave: (Expense) -> Unit
) {
    var title by remember(expense.id) { mutableStateOf(expense.title) }
    var amount by remember(expense.id) { mutableStateOf(expense.amount.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Expense") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") },
                    singleLine = true
                )
                Spacer(Modifier.padding(1.dp))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = amount.toDoubleOrNull()
                    if (title.isNotBlank() && parsed != null && parsed > 0) {
                        onSave(expense.copy(title = title.trim(), amount = parsed))
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
}
