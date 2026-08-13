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

    /**
     * Every expense the server holds for this user, following the cursor until
     * the pages run out.
     *
     * A page cap stops a corrupted or misbehaving cursor from looping forever;
     * at the server's maximum page size it still covers far more history than
     * any real account will hold.
     */
    suspend fun fetchAllExpenses(): List<ExpenseSyncPayload> {
        val token = bearerToken()
        val collected = mutableListOf<ExpenseSyncPayload>()
        var after = 0

        repeat(MAX_PAGES) {
            val response = api.listExpenses(token, after = after, limit = PAGE_SIZE)
            if (!response.isSuccessful) throw HttpException(response)

            val page = response.body() ?: return collected
            collected += page.items
            after = page.nextAfter ?: return collected
        }
        return collected
    }

    private suspend fun bearerToken(): String {
        val user = firebaseAuth.currentUser
            ?: error("Cannot reach the server without an authenticated Firebase user.")
        val token = user.getIdToken(false).await().token
            ?: error("Cannot reach the server without a Firebase ID token.")
        return "Bearer $token"
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

    private companion object {
        const val PAGE_SIZE = 200
        const val MAX_PAGES = 500
    }
}
