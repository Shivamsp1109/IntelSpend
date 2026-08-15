package com.spendwise.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MySqlBudgetApi {
    @POST("budgets/sync")
    suspend fun upsertBudget(
        @Header("Authorization") bearerToken: String,
        @Body budget: BudgetSyncPayload
    ): Response<Unit>

    @DELETE("budgets/sync/{localId}")
    suspend fun deleteBudget(
        @Header("Authorization") bearerToken: String,
        @Path("localId") localId: Int
    ): Response<Unit>

    @GET("budgets")
    suspend fun listBudgets(
        @Header("Authorization") bearerToken: String,
        @Query("after") after: Int,
        @Query("limit") limit: Int
    ): Response<RestorePage<BudgetSyncPayload>>
}
