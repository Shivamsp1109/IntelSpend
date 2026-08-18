package com.spendwise.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MySqlInsuranceApi {
    @POST("insurance/sync")
    suspend fun upsertPolicy(
        @Header("Authorization") bearerToken: String,
        @Body policy: InsuranceSyncPayload
    ): Response<Unit>

    @DELETE("insurance/sync/{localId}")
    suspend fun deletePolicy(
        @Header("Authorization") bearerToken: String,
        @Path("localId") localId: Int
    ): Response<Unit>

    @GET("insurance")
    suspend fun listPolicies(
        @Header("Authorization") bearerToken: String,
        @Query("after") after: Int,
        @Query("limit") limit: Int
    ): Response<RestorePage<InsuranceRestorePayload>>
}

interface MySqlRiskProfileApi {
    @POST("risk-profile/sync")
    suspend fun upsertProfile(
        @Header("Authorization") bearerToken: String,
        @Body profile: RiskProfileSyncPayload
    ): Response<Unit>

    @DELETE("risk-profile/sync")
    suspend fun deleteProfile(
        @Header("Authorization") bearerToken: String
    ): Response<Unit>

    @GET("risk-profile")
    suspend fun getProfile(
        @Header("Authorization") bearerToken: String
    ): Response<RiskProfileResponse>
}
