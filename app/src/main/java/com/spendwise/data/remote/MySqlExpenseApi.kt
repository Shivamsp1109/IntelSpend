package com.spendwise.data.remote

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface MySqlExpenseApi {
    @POST("expenses/sync")
    suspend fun upsertExpense(
        @Header("Authorization") bearerToken: String,
        @Body expense: ExpenseSyncPayload
    )
}
