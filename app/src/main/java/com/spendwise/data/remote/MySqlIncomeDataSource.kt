package com.spendwise.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.spendwise.data.local.IncomeEntity
import kotlinx.coroutines.tasks.await
import retrofit2.HttpException
import javax.inject.Inject

class MySqlIncomeDataSource @Inject constructor(
    private val api: MySqlIncomeApi,
    private val firebaseAuth: FirebaseAuth
) {
    suspend fun upsertIncome(income: IncomeEntity) {
        val user = firebaseAuth.currentUser
            ?: error("Cannot sync income without an authenticated Firebase user.")
        val token = user.getIdToken(false).await().token
            ?: error("Cannot sync income without a Firebase ID token.")
        val response = api.upsertIncome(
            bearerToken = "Bearer $token",
            income = income.toSyncPayload(uid = user.uid)
        )
        if (!response.isSuccessful) throw HttpException(response)
    }
}

