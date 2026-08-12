package com.spendwise.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.presentation.components.CategoryBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPickerSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onCategorySelected: (ExpenseCategory) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Select Category",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(ExpenseCategory.entries.toTypedArray()) { category ->
                    CategoryBadge(
                        category = category,
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable {
                                onCategorySelected(category)
                                onDismiss()
                            }
                    )
                }
            }
        }
    }
}
