package com.spendwise.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface MySqlGoalApi {
    @POST("goals/sync")
    suspend fun upsertGoal(
        @Header("Authorization") bearerToken: String,
        @Body goal: GoalSyncPayload
    ): Response<Unit>
}
