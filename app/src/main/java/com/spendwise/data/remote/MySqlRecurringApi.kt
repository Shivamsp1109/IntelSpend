package com.spendwise.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Header
import retrofit2.http.POST

interface MySqlRecurringApi {
    @POST("recurring/sync")
    suspend fun upsertRecurring(
        @Header("Authorization") bearerToken: String,
        @Body recurring: RecurringSyncPayload
    ): Response<Unit>

    @POST("recurring/link")
    suspend fun linkExpense(
        @Header("Authorization") bearerToken: String,
        @Body link: RecurringLinkPayload
    ): Response<Unit>

    @DELETE("recurring/link")
    suspend fun unlinkExpense(
        @Header("Authorization") bearerToken: String,
        @Body link: RecurringLinkPayload
    ): Response<Unit>
}
