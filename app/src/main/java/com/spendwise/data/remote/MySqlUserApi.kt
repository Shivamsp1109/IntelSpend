package com.spendwise.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path

interface MySqlUserApi {
    @GET("users/{uid}")
    suspend fun getUserProfile(
        @Header("Authorization") bearerToken: String,
        @Path("uid") uid: String
    ): Response<UserProfilePayload>

    @PUT("users/{uid}")
    suspend fun upsertUserProfile(
        @Header("Authorization") bearerToken: String,
        @Path("uid") uid: String,
        @Body profile: UserProfilePayload
    )
}
