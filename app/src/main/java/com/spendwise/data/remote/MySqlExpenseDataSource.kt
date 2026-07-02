package com.spendwise.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.spendwise.data.local.ExpenseEntity
import kotlinx.coroutines.tasks.await
import retrofit2.HttpException
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
        val response = api.upsertExpense(
            bearerToken = "Bearer $token",
            expense = expense.toSyncPayload(uid = user.uid)
        )
        if (!response.isSuccessful) throw HttpException(response)
    }

    suspend fun deleteExpense(localId: Int) {
        val user = firebaseAuth.currentUser
            ?: error("Cannot sync expense without an authenticated Firebase user.")
        val token = user.getIdToken(false).await().token
            ?: error("Cannot sync expense without a Firebase ID token.")
        val response = api.deleteExpense(
            bearerToken = "Bearer $token",
            localId = localId
        )
        if (!response.isSuccessful) throw HttpException(response)
    }
}
