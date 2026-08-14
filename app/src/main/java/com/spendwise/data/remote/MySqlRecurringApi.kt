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

    @POST("recurring/dismissals")
    suspend fun upsertDismissal(
        @Header("Authorization") bearerToken: String,
        @Body dismissal: RecurringDismissalPayload
    ): Response<Unit>

    // ── Restore reads ─────────────────────────────────────────────────────────

    @retrofit2.http.GET("recurring")
    suspend fun listRecurring(
        @Header("Authorization") bearerToken: String,
        @retrofit2.http.Query("after") after: Int,
        @retrofit2.http.Query("limit") limit: Int
    ): Response<RestorePage<RecurringSyncPayload>>

    @retrofit2.http.GET("recurring/links")
    suspend fun listLinks(
        @Header("Authorization") bearerToken: String,
        @retrofit2.http.Query("after") after: Int,
        @retrofit2.http.Query("limit") limit: Int
    ): Response<RestorePage<RecurringLinkRow>>

    @retrofit2.http.GET("recurring/dismissals")
    suspend fun listDismissals(
        @Header("Authorization") bearerToken: String,
        @retrofit2.http.Query("after") after: Int,
        @retrofit2.http.Query("limit") limit: Int
    ): Response<RestorePage<RecurringDismissalRow>>
}
