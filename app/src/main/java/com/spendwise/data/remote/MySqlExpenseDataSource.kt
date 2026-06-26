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
        val user = firebaseAuth.currentUser ?: return
        val token = user.getIdToken(false).await().token ?: return
        api.upsertExpense(
            bearerToken = "Bearer $token",
            expense = expense.toSyncPayload(uid = user.uid)
        )
    }
}
