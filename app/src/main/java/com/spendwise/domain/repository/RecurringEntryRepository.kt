package com.spendwise.domain.repository

import com.spendwise.domain.model.RecurringEntry
import kotlinx.coroutines.flow.Flow

interface RecurringEntryRepository {
    fun observeRecurring(): Flow<List<RecurringEntry>>
    suspend fun getById(id: Int): RecurringEntry?
    suspend fun addRecurring(entry: RecurringEntry)
    suspend fun updateRecurring(entry: RecurringEntry)
    suspend fun deleteRecurring(entry: RecurringEntry)
    /** Link an expense occurrence to this recurring entry. */
    suspend fun linkExpense(recurringId: Int, expenseId: Int)
    /** Unlink a previously linked expense. */
    suspend fun unlinkExpense(recurringId: Int, expenseId: Int)
    /** Sync all pending recurring entries to the remote backend. */
    suspend fun syncPendingRecurring()
}
