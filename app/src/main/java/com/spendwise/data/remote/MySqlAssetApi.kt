package com.spendwise.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MySqlAssetApi {
    @POST("assets/sync")
    suspend fun upsertAsset(
        @Header("Authorization") bearerToken: String,
        @Body asset: AssetSyncPayload
    ): Response<Unit>

    @DELETE("assets/sync/{localId}")
    suspend fun deleteAsset(
        @Header("Authorization") bearerToken: String,
        @Path("localId") localId: Int
    ): Response<Unit>

    @GET("assets")
    suspend fun listAssets(
        @Header("Authorization") bearerToken: String,
        @Query("after") after: Int,
        @Query("limit") limit: Int
    ): Response<RestorePage<AssetRestorePayload>>
}
