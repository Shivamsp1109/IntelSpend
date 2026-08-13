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

    /** One page of the user's stored incomes; see MySqlExpenseApi.listExpenses. */
    @retrofit2.http.GET("incomes")
    suspend fun listIncomes(
        @Header("Authorization") bearerToken: String,
        @retrofit2.http.Query("after") after: Int,
        @retrofit2.http.Query("limit") limit: Int
    ): Response<RestorePage<IncomeSyncPayload>>

    @retrofit2.http.DELETE("incomes/sync/{localId}")
    suspend fun deleteIncome(
        @Header("Authorization") bearerToken: String,
        @retrofit2.http.Path("localId") localId: Int
    ): Response<Unit>
}

