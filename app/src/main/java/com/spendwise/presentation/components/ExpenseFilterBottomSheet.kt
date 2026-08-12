package com.spendwise.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spendwise.domain.model.ExpenseFilterState
import com.spendwise.domain.model.ExpenseSortOrder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseSortFilterSheet(
    filterState: ExpenseFilterState,
    onFilterStateChange: (ExpenseFilterState) -> Unit,
    onDismiss: () -> Unit,
    distinctCategories: List<String>,
    distinctTitles: List<String>,
    distinctMerchants: List<String>,
    distinctCurrencies: List<String>
) {
    var showAdvanced by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (!showAdvanced) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Sort By", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))

                ExpenseSortOrder.entries.forEach { order ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onFilterStateChange(filterState.copy(sortOrder = order))
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(order.label, modifier = Modifier.weight(1f))
                        if (filterState.sortOrder == order) {
                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = SpendWisePurple)
                        }
                    }
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                TextButton(
                    onClick = { showAdvanced = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Advanced Filter", color = SpendWisePurple)
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        } else {
            AdvancedFilterContent(
                filterState = filterState,
                onFilterStateChange = onFilterStateChange,
                distinctCategories = distinctCategories,
                distinctTitles = distinctTitles,
                distinctMerchants = distinctMerchants,
                distinctCurrencies = distinctCurrencies,
                onBack = { showAdvanced = false },
                onApply = { onDismiss() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedFilterContent(
    filterState: ExpenseFilterState,
    onFilterStateChange: (ExpenseFilterState) -> Unit,
    distinctCategories: List<String>,
    distinctTitles: List<String>,
    distinctMerchants: List<String>,
    distinctCurrencies: List<String>,
    onBack: () -> Unit,
    onApply: () -> Unit
) {
    var state by remember { mutableStateOf(filterState) }
    var showDatePicker by remember { mutableStateOf(false) }
    val dateRangePickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = state.startDate,
        initialSelectedEndDateMillis = state.endDate
    )

    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Advanced Filter", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onFilterStateChange(state); onApply() }) { Text("Apply") }
        }

        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Page Size", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 25, 50, 75, 100).forEach { size ->
                        FilterChip(
                            selected = state.pageSize == size,
                            onClick = { state = state.copy(pageSize = size) },
                            label = { Text(size.toString()) }
                        )
                    }
                }
            }

            item {
                Text("Date Range", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (state.startDate != null) com.spendwise.util.DateUtils.formatDate(state.startDate!!) else "Start Date")
                    }
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (state.endDate != null) com.spendwise.util.DateUtils.formatDate(state.endDate!!) else "End Date")
                    }
                }
                if (state.startDate != null || state.endDate != null) {
                    TextButton(onClick = {
                        state = state.copy(startDate = null, endDate = null)
                        dateRangePickerState.setSelection(null, null)
                    }) {
                        Text("Clear Dates", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            item { FilterSection("Categories", distinctCategories, state.categories) { state = state.copy(categories = it) } }
            item { FilterSection("Titles", distinctTitles, state.titles) { state = state.copy(titles = it) } }
            item { FilterSection("Merchants", distinctMerchants, state.merchants) { state = state.copy(merchants = it) } }
            item { FilterSection("Currencies", distinctCurrencies, state.currencies) { state = state.copy(currencies = it) } }
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state = state.copy(
                        startDate = dateRangePickerState.selectedStartDateMillis,
                        endDate = dateRangePickerState.selectedEndDateMillis
                    )
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DateRangePicker(
                state = dateRangePickerState,
                title = { Text(text = "Select Date Range", modifier = Modifier.padding(16.dp)) },
                headline = { Text(text = "Transactions between dates", modifier = Modifier.padding(horizontal = 16.dp)) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun FilterSection(
    title: String,
    options: List<String>,
    selected: Set<String>,
    onSelectionChange: (Set<String>) -> Unit
) {
    if (options.isEmpty()) return
    
    var searchQuery by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val filteredOptions = options.filter { it.contains(searchQuery, ignoreCase = true) }
    val visibleOptions = if (expanded) filteredOptions else filteredOptions.take(10)

    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (options.size > 5) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search...") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
        }
        
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            visibleOptions.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val newSet = if (selected.contains(option)) selected - option else selected + option
                            onSelectionChange(newSet)
                        }
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(checked = selected.contains(option), onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(option)
                }
            }
            if (!expanded && filteredOptions.size > 10) {
                TextButton(onClick = { expanded = true }) {
                    Text("+ ${filteredOptions.size - 10} more...", color = SpendWisePurple)
                }
            } else if (expanded && filteredOptions.size > 10) {
                TextButton(onClick = { expanded = false }) {
                    Text("Show less", color = SpendWisePurple)
                }
            }
        }
    }
}
