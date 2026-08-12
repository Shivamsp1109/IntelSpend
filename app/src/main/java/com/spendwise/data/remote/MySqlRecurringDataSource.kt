package com.spendwise.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.spendwise.data.local.RecurringEntity
import kotlinx.coroutines.tasks.await
import retrofit2.HttpException
import javax.inject.Inject

class MySqlRecurringDataSource @Inject constructor(
    private val api: MySqlRecurringApi,
    private val firebaseAuth: FirebaseAuth
) {
    private suspend fun bearerToken(): String {
        val user = firebaseAuth.currentUser
            ?: error("Cannot sync recurring entry without an authenticated Firebase user.")
        return "Bearer ${
            user.getIdToken(false).await().token
                ?: error("Cannot sync recurring entry without a Firebase ID token.")
        }"
    }

    suspend fun upsertRecurring(entry: RecurringEntity) {
        val uid = firebaseAuth.currentUser?.uid
            ?: error("Cannot sync recurring entry without an authenticated Firebase user.")
        val response = api.upsertRecurring(
            bearerToken = bearerToken(),
            recurring = entry.toSyncPayload(uid = uid)
        )
        if (!response.isSuccessful) throw HttpException(response)
    }

    suspend fun linkExpense(recurringId: Int, expenseId: Int) {
        val uid = firebaseAuth.currentUser?.uid
            ?: error("Cannot link expense without an authenticated Firebase user.")
        val response = api.linkExpense(
            bearerToken = bearerToken(),
            link = RecurringLinkPayload(
                uid = uid,
                recurringLocalId = recurringId,
                expenseLocalId = expenseId
            )
        )
        if (!response.isSuccessful) throw HttpException(response)
    }

    suspend fun unlinkExpense(recurringId: Int, expenseId: Int) {
        val uid = firebaseAuth.currentUser?.uid
            ?: error("Cannot unlink expense without an authenticated Firebase user.")
        val response = api.unlinkExpense(
            bearerToken = bearerToken(),
            link = RecurringLinkPayload(
                uid = uid,
                recurringLocalId = recurringId,
                expenseLocalId = expenseId
            )
        )
        if (!response.isSuccessful) throw HttpException(response)
    }
}
