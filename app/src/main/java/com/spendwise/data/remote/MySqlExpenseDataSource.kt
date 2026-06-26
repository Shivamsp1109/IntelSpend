package com.spendwise.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.spendwise.data.local.ExpenseEntity
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

class MySqlExpenseDataSource @Inject constructor(
    private val api: MySqlExpenseApi,
    private val firebaseAuth: FirebaseAuth
) {
    suspend fun upsertExpense(expense: ExpenseEntity) {
        val user = firebaseAuth.currentUser
            ?: error("Cannot sync expense without an authenticated Firebase user.")
        val token = user.getIdToken(false).await().token
            ?: error("Cannot sync expense without a Firebase ID token.")
        api.upsertExpense(
            bearerToken = "Bearer $token",
            expense = expense.toSyncPayload(uid = user.uid)
        )
    }
}
