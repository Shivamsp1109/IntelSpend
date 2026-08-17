package com.spendwise.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.spendwise.data.local.LoanDetailsEntity
import javax.inject.Inject
import kotlinx.coroutines.tasks.await
import retrofit2.HttpException

class MySqlLoanDetailsDataSource @Inject constructor(
    private val api: MySqlLoanDetailsApi,
    private val firebaseAuth: FirebaseAuth
) {
    private suspend fun bearerToken(): String {
        val user = firebaseAuth.currentUser
            ?: error("Cannot sync loan terms without an authenticated Firebase user.")
        return "Bearer ${
            user.getIdToken(false).await().token
                ?: error("Cannot sync loan terms without a Firebase ID token.")
        }"
    }

    suspend fun upsertLoanDetails(loan: LoanDetailsEntity) {
        val uid = firebaseAuth.currentUser?.uid
            ?: error("Cannot sync loan terms without an authenticated Firebase user.")
        val response = api.upsertLoanDetails(bearerToken(), loan.toSyncPayload(uid))
        if (!response.isSuccessful) throw HttpException(response)
    }

    suspend fun deleteLoanDetails(recurringLocalId: Int) {
        val response = api.deleteLoanDetails(bearerToken(), recurringLocalId)
        if (!response.isSuccessful) throw HttpException(response)
    }

    suspend fun fetchAllLoanDetails(): List<LoanDetailsRestorePayload> {
        val token = bearerToken()
        val collected = mutableListOf<LoanDetailsRestorePayload>()
        var after = 0

        repeat(MAX_PAGES) {
            val response = api.listLoanDetails(token, after = after, limit = PAGE_SIZE)
            if (!response.isSuccessful) throw HttpException(response)

            val page = response.body() ?: return collected
            collected += page.items
            after = page.nextAfter ?: return collected
        }
        return collected
    }

    private companion object {
        const val PAGE_SIZE = 200
        const val MAX_PAGES = 20
    }
}
