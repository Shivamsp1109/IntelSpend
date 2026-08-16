package com.spendwise.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExpenseSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun enqueueImmediateSync() {
        enqueueSyncOneShot<ExpenseSyncWorker>(context, "expense_mysql_immediate_sync")
    }
}
