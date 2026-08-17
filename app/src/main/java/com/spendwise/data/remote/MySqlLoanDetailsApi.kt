package com.spendwise.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MySqlLoanDetailsApi {
    @POST("loan-details/sync")
    suspend fun upsertLoanDetails(
        @Header("Authorization") bearerToken: String,
        @Body loan: LoanDetailsSyncPayload
    ): Response<Unit>

    @DELETE("loan-details/sync/{recurringLocalId}")
    suspend fun deleteLoanDetails(
        @Header("Authorization") bearerToken: String,
        @Path("recurringLocalId") recurringLocalId: Int
    ): Response<Unit>

    @GET("loan-details")
    suspend fun listLoanDetails(
        @Header("Authorization") bearerToken: String,
        @Query("after") after: Int,
        @Query("limit") limit: Int
    ): Response<RestorePage<LoanDetailsRestorePayload>>
}
