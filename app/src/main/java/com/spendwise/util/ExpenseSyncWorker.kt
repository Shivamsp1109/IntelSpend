package com.spendwise.util

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spendwise.domain.repository.ExpenseRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ExpenseSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: ExpenseRepository
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        repository.syncPendingExpenses()
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { Result.retry() }
    )
}
