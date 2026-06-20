package com.spendwise.util

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppMetrics @Inject constructor(
    private val analytics: FirebaseAnalytics,
    private val crashlytics: FirebaseCrashlytics
) {
    fun logExpenseSaved(category: String, amount: Double) {
        analytics.logEvent("expense_saved", Bundle().apply {
            putString("category", category)
            putDouble("amount", amount)
        })
    }

    fun logExpenseDeleted(category: String) {
        analytics.logEvent("expense_deleted", Bundle().apply {
            putString("category", category)
        })
    }

    fun recordNonFatal(error: Throwable) {
        crashlytics.recordException(error)
    }
}
