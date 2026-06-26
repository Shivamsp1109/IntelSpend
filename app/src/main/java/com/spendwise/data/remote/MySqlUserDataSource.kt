package com.spendwise.data.remote

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

class MySqlUserDataSource @Inject constructor(
    private val api: MySqlUserApi,
    private val firebaseAuth: FirebaseAuth
) {
    suspend fun getCurrentUserProfile(): UserProfilePayload? {
        val user = firebaseAuth.currentUser ?: return null
        val token = currentBearerToken() ?: return null
        return api.getUserProfile(token, user.uid).body()
    }

    suspend fun upsertCurrentUserProfile(profile: UserProfilePayload) {
        val token = currentBearerToken() ?: return
        api.upsertUserProfile(token, profile.uid, profile)
    }

    private suspend fun currentBearerToken(): String? {
        val user = firebaseAuth.currentUser ?: return null
        val token = user.getIdToken(false).await().token ?: return null
        return "Bearer $token"
    }
}
