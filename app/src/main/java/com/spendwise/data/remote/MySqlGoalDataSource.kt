package com.spendwise.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.spendwise.data.local.GoalEntity
import kotlinx.coroutines.tasks.await
import retrofit2.HttpException
import javax.inject.Inject

class MySqlGoalDataSource @Inject constructor(
    private val api: MySqlGoalApi,
    private val firebaseAuth: FirebaseAuth
) {
    suspend fun upsertGoal(goal: GoalEntity) {
        val user = firebaseAuth.currentUser
            ?: error("Cannot sync goal without an authenticated Firebase user.")
        val token = user.getIdToken(false).await().token
            ?: error("Cannot sync goal without a Firebase ID token.")
        val response = api.upsertGoal(
            bearerToken = "Bearer $token",
            goal = goal.toSyncPayload(uid = user.uid)
        )
        if (!response.isSuccessful) throw HttpException(response)
    }
}
