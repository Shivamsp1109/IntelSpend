package com.spendwise.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.data.local.ExpenseDao
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.MerchantGroup
import com.spendwise.domain.model.TransactionNature
import com.spendwise.domain.usecase.RecategoriseMerchantUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the bulk recategorisation screen.
 *
 * Reads merchant groups straight from the DAO as a Flow, so a correction is
 * reflected the moment it lands rather than needing a manual refresh — the user
 * works down a list, and the list should shorten as they go.
 */
@HiltViewModel
class RecategoriseViewModel @Inject constructor(
    expenseDao: ExpenseDao,
    private val recategoriseMerchantUseCase: RecategoriseMerchantUseCase
) : ViewModel() {

    private val filter = MutableStateFlow(RecategoriseFilter.NeedsAttention)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val groups = expenseDao.observeMerchantGroups().map { rows ->
        rows.map { row ->
            MerchantGroup(
                merchant = row.merchant,
                category = ExpenseCategory.fromLabel(row.category),
                nature = TransactionNature.fromName(row.nature),
                count = row.count,
                total = row.total,
                latestDate = row.latestDate,
                currency = Currency.fromCode(row.currency)
            )
        }
    }

    val uiState: StateFlow<RecategoriseUiState> = combine(groups, filter) { all, active ->
        val visible = when (active) {
            RecategoriseFilter.NeedsAttention -> all.filter { it.isUncategorised }
            RecategoriseFilter.All -> all
        }
        RecategoriseUiState(
            filter = active,
            // Ranked by what fixing it is worth, so the work with the largest
            // effect on the user's figures is at the top.
            groups = visible.sortedByDescending { it.impact },
            needsAttentionCount = all.count { it.isUncategorised },
            totalGroups = all.size
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecategoriseUiState())

    fun setFilter(value: RecategoriseFilter) {
        filter.value = value
    }

    fun apply(group: MerchantGroup, category: ExpenseCategory, nature: TransactionNature) {
        viewModelScope.launch {
            val updated = recategoriseMerchantUseCase(group, category, nature)
            _message.value = when {
                updated == 0 -> "Nothing to update for ${group.merchant}."
                updated == 1 -> "Updated 1 transaction and remembered ${group.merchant}."
                else -> "Updated $updated transactions and remembered ${group.merchant}."
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

enum class RecategoriseFilter(val label: String) {
    /** Uncategorised and still counted as spending — where the payoff is. */
    NeedsAttention("Needs attention"),
    All("All merchants")
}

data class RecategoriseUiState(
    val filter: RecategoriseFilter = RecategoriseFilter.NeedsAttention,
    val groups: List<MerchantGroup> = emptyList(),
    val needsAttentionCount: Int = 0,
    val totalGroups: Int = 0
)
