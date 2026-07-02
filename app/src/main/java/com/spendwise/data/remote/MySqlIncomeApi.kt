package com.spendwise.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface MySqlIncomeApi {
    @POST("incomes/sync")
    suspend fun upsertIncome(
        @Header("Authorization") bearerToken: String,
        @Body income: IncomeSyncPayload
    ): Response<Unit>
}

