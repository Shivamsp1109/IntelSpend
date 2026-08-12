package com.spendwise.presentation.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spendwise.data.ingestion.IngestionOrchestrator
import com.spendwise.data.ingestion.IngestionResult
import com.spendwise.data.ingestion.category.LearnedCategoryStore
import com.spendwise.data.ingestion.mapper.Either
import com.spendwise.data.ingestion.mapper.TransactionMapper
import com.spendwise.data.ingestion.model.RawTransaction
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.usecase.AddExpensesBatchUseCase
import com.spendwise.domain.usecase.AddIncomesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class ImportState { IDLE, PROCESSING, PASSWORD_REQUIRED, REVIEW, SAVING, COMPLETE, ERROR }

data class ImportUiState(
    val state: ImportState = ImportState.IDLE,
    val fileName: String = "",
    val errorMessage: String? = null,
    val transactions: List<RawTransaction> = emptyList(),
    val savedCount: Int = 0,
    /** Set when a password was supplied and rejected, so the prompt can say so. */
    val passwordRejected: Boolean = false,
    /** Non-fatal problem shown above the review list, e.g. smart extraction was unreachable. */
    val warning: String? = null
)

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val orchestrator: IngestionOrchestrator,
    private val transactionMapper: TransactionMapper,
    private val addExpensesBatchUseCase: AddExpensesBatchUseCase,
    private val addIncomesUseCase: AddIncomesUseCase,
    private val learnedCategoryStore: LearnedCategoryStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    private var currentFileUri: Uri? = null
    private var currentSourceType: String = "image"

    fun processFile(uri: Uri, fileName: String, sourceType: String) {
        currentFileUri = uri
        currentSourceType = sourceType
        _uiState.update {
            it.copy(
                state = ImportState.PROCESSING,
                fileName = fileName,
                errorMessage = null,
                passwordRejected = false,
                warning = null
            )
        }
        run(uri, fileName, sourceType, password = null)
    }

    /** Retries the current file with a password the user just entered. */
    fun submitPassword(password: String) {
        val uri = currentFileUri ?: return
        val fileName = _uiState.value.fileName
        _uiState.update { it.copy(state = ImportState.PROCESSING, errorMessage = null) }
        run(uri, fileName, currentSourceType, password)
    }

    fun cancelPassword() {
        _uiState.value = ImportUiState()
        currentFileUri = null
    }

    private fun run(uri: Uri, fileName: String, sourceType: String, password: String?) {
        viewModelScope.launch {
            when (val result = orchestrator.process(uri, fileName, sourceType, password)) {
                is IngestionResult.Success -> _uiState.update {
                    it.copy(
                        state = ImportState.REVIEW,
                        transactions = result.transactions,
                        passwordRejected = false,
                        warning = result.warning
                    )
                }
                is IngestionResult.PasswordRequired -> _uiState.update {
                    it.copy(
                        state = ImportState.PASSWORD_REQUIRED,
                        passwordRejected = result.wrongPassword,
                        errorMessage = null
                    )
                }
                is IngestionResult.Error -> _uiState.update {
                    it.copy(state = ImportState.ERROR, errorMessage = result.message)
                }
            }
        }
    }

    fun toggleSelection(index: Int) {
        _uiState.update { state ->
            val mutableList = state.transactions.toMutableList()
            if (index in mutableList.indices) {
                val tx = mutableList[index]
                mutableList[index] = tx.copy(isSelected = !tx.isSelected)
            }
            state.copy(transactions = mutableList)
        }
    }

    fun updateTransaction(index: Int, updatedTx: RawTransaction) {
        _uiState.update { state ->
            val mutableList = state.transactions.toMutableList()
            if (index in mutableList.indices) {
                val oldTx = mutableList[index]
                mutableList[index] = updatedTx
                
                // If user manually changed the category, save to learned categories
                if (oldTx.category != updatedTx.category && updatedTx.merchant != null) {
                    viewModelScope.launch {
                        learnedCategoryStore.learnCategory(updatedTx.merchant, updatedTx.category.name)
                    }
                }
            }
            state.copy(transactions = mutableList)
        }
    }

    fun saveSelected() {
        _uiState.update { it.copy(state = ImportState.SAVING) }
        val selected = _uiState.value.transactions.filter { it.isSelected }
        
        viewModelScope.launch {
            val expensesToSave = mutableListOf<com.spendwise.domain.model.Expense>()
            val incomesToSave = mutableListOf<com.spendwise.domain.model.Income>()

            for (raw in selected) {
                when (val mapped = transactionMapper.map(raw)) {
                    is Either.Left -> expensesToSave.add(mapped.value)
                    is Either.Right -> incomesToSave.add(mapped.value)
                }
            }

            if (expensesToSave.isNotEmpty()) {
                addExpensesBatchUseCase(expensesToSave)
            }
            if (incomesToSave.isNotEmpty()) {
                addIncomesUseCase(incomesToSave)
            }

            _uiState.update { it.copy(state = ImportState.COMPLETE, savedCount = expensesToSave.size + incomesToSave.size) }
        }
    }

    fun deleteFileAndReset(cacheDir: File) {
        _uiState.value.fileName.takeIf { it.isNotEmpty() }?.let { name ->
            val file = File(cacheDir, name)
            if (file.exists()) {
                file.delete()
            } else {
                android.util.Log.d("ImportViewModel", "Temp file $name not found for deletion. (Likely already cleaned up by reader).")
            }
        }
        _uiState.value = ImportUiState()
        currentFileUri = null
    }
}
