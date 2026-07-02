package com.spendwise.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface MySqlExpenseApi {
    @POST("expenses/sync")
    suspend fun upsertExpense(
        @Header("Authorization") bearerToken: String,
        @Body expense: ExpenseSyncPayload
    ): Response<Unit>

    @retrofit2.http.DELETE("expenses/sync/{localId}")
    suspend fun deleteExpense(
        @Header("Authorization") bearerToken: String,
        @retrofit2.http.Path("localId") localId: Int
    ): Response<Unit>
}
