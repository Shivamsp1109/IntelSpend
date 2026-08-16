package com.spendwise.domain.usecase

import android.util.Log
import com.spendwise.domain.repository.AuthRepository
import com.spendwise.domain.repository.ExpenseRepository
import com.spendwise.domain.repository.GoalRepository
import com.spendwise.domain.repository.IncomeRepository
import com.spendwise.domain.repository.RecurringEntryRepository
import javax.inject.Inject

/**
 * Signs out, flushing anything not yet uploaded first.
 *
 * The flush is the point. Local data is deliberately *not* wiped here — a user
 * signing out and back in should find their history intact rather than
 * re-downloading it, and wiping while offline would destroy edits that had
 * nowhere to go. It is cleared later, and only if a different account actually
 * signs in (see [PrepareUserSessionUseCase]).
 *
 * But that later clearing is the last chance those rows get, and by then the
 * token needed to upload them is gone. So this is the moment to push whatever is
 * pending: still authenticated, and with the user waiting anyway.
 *
 * Best-effort. Somebody signing out on a plane must still be signed out, so a
 * failed upload is logged rather than blocking; their data stays on the device
 * and syncs on their next session.
 */
class SignOutUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val expenseRepository: ExpenseRepository,
    private val incomeRepository: IncomeRepository,
    private val goalRepository: GoalRepository,
    private val recurringRepository: RecurringEntryRepository
) {
    suspend operator fun invoke() {
        flushPendingWork()
        authRepository.logout()
    }

    private suspend fun flushPendingWork() {
        runCatching { expenseRepository.syncPendingExpenses() }
            .onFailure { Log.w(TAG, "Could not flush expenses before signing out.", it) }
        runCatching { incomeRepository.syncPendingIncomes() }
            .onFailure { Log.w(TAG, "Could not flush incomes before signing out.", it) }
        runCatching { goalRepository.syncPendingGoals() }
            .onFailure { Log.w(TAG, "Could not flush goals before signing out.", it) }
        runCatching { recurringRepository.syncPendingRecurring() }
            .onFailure { Log.w(TAG, "Could not flush commitments before signing out.", it) }
    }

    private companion object {
        const val TAG = "SignOut"
    }
}
