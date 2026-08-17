package com.spendwise.domain.repository

import com.spendwise.domain.model.LoanDetails
import kotlinx.coroutines.flow.Flow

interface LoanDetailsRepository {
    fun observeAll(): Flow<List<LoanDetails>>

    /** The terms for one tracked commitment, or null when none were entered. */
    fun observeForRecurring(recurringId: Int): Flow<LoanDetails?>

    suspend fun getForRecurring(recurringId: Int): LoanDetails?
    suspend fun save(details: LoanDetails)
    suspend fun delete(details: LoanDetails)
    suspend fun syncPendingLoanDetails()
}
