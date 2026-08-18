package com.spendwise.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.spendwise.data.local.InsurancePolicyEntity
import com.spendwise.data.local.RiskAssessmentEntity
import javax.inject.Inject
import kotlinx.coroutines.tasks.await
import retrofit2.HttpException

class MySqlInsuranceDataSource @Inject constructor(
    private val api: MySqlInsuranceApi,
    private val firebaseAuth: FirebaseAuth
) {
    private suspend fun bearerToken(): String {
        val user = firebaseAuth.currentUser
            ?: error("Cannot sync a policy without an authenticated Firebase user.")
        return "Bearer ${
            user.getIdToken(false).await().token
                ?: error("Cannot sync a policy without a Firebase ID token.")
        }"
    }

    suspend fun upsertPolicy(policy: InsurancePolicyEntity) {
        val uid = firebaseAuth.currentUser?.uid
            ?: error("Cannot sync a policy without an authenticated Firebase user.")
        val response = api.upsertPolicy(bearerToken(), policy.toSyncPayload(uid))
        if (!response.isSuccessful) throw HttpException(response)
    }

    suspend fun deletePolicy(localId: Int) {
        val response = api.deletePolicy(bearerToken(), localId)
        if (!response.isSuccessful) throw HttpException(response)
    }

    suspend fun fetchAllPolicies(): List<InsuranceRestorePayload> {
        val token = bearerToken()
        val collected = mutableListOf<InsuranceRestorePayload>()
        var after = 0

        repeat(MAX_PAGES) {
            val response = api.listPolicies(token, after = after, limit = PAGE_SIZE)
            if (!response.isSuccessful) throw HttpException(response)

            val page = response.body() ?: return collected
            collected += page.items
            after = page.nextAfter ?: return collected
        }
        return collected
    }

    private companion object {
        const val PAGE_SIZE = 200
        const val MAX_PAGES = 20
    }
}

class MySqlRiskProfileDataSource @Inject constructor(
    private val api: MySqlRiskProfileApi,
    private val firebaseAuth: FirebaseAuth
) {
    private suspend fun bearerToken(): String {
        val user = firebaseAuth.currentUser
            ?: error("Cannot sync a risk profile without an authenticated Firebase user.")
        return "Bearer ${
            user.getIdToken(false).await().token
                ?: error("Cannot sync a risk profile without a Firebase ID token.")
        }"
    }

    suspend fun upsertProfile(profile: RiskAssessmentEntity) {
        val uid = firebaseAuth.currentUser?.uid
            ?: error("Cannot sync a risk profile without an authenticated Firebase user.")
        val response = api.upsertProfile(bearerToken(), profile.toSyncPayload(uid))
        if (!response.isSuccessful) throw HttpException(response)
    }

    suspend fun deleteProfile() {
        val response = api.deleteProfile(bearerToken())
        if (!response.isSuccessful) throw HttpException(response)
    }

    suspend fun fetchProfile(): RiskProfileRestorePayload? {
        val response = api.getProfile(bearerToken())
        if (!response.isSuccessful) throw HttpException(response)
        return response.body()?.profile
    }
}
