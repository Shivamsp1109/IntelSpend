package com.spendwise.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.spendwise.data.local.AssetEntity
import javax.inject.Inject
import kotlinx.coroutines.tasks.await
import retrofit2.HttpException

class MySqlAssetDataSource @Inject constructor(
    private val api: MySqlAssetApi,
    private val firebaseAuth: FirebaseAuth
) {
    private suspend fun bearerToken(): String {
        val user = firebaseAuth.currentUser
            ?: error("Cannot sync a holding without an authenticated Firebase user.")
        return "Bearer ${
            user.getIdToken(false).await().token
                ?: error("Cannot sync a holding without a Firebase ID token.")
        }"
    }

    suspend fun upsertAsset(asset: AssetEntity) {
        val uid = firebaseAuth.currentUser?.uid
            ?: error("Cannot sync a holding without an authenticated Firebase user.")
        val response = api.upsertAsset(bearerToken(), asset.toSyncPayload(uid))
        if (!response.isSuccessful) throw HttpException(response)
    }

    suspend fun deleteAsset(localId: Int) {
        val response = api.deleteAsset(bearerToken(), localId)
        if (!response.isSuccessful) throw HttpException(response)
    }

    suspend fun fetchAllAssets(): List<AssetRestorePayload> {
        val token = bearerToken()
        val collected = mutableListOf<AssetRestorePayload>()
        var after = 0

        repeat(MAX_PAGES) {
            val response = api.listAssets(token, after = after, limit = PAGE_SIZE)
            if (!response.isSuccessful) throw HttpException(response)

            val page = response.body() ?: return collected
            collected += page.items
            after = page.nextAfter ?: return collected
        }
        return collected
    }

    private companion object {
        const val PAGE_SIZE = 200

        /** There will never be many; this is only a guard against a looping server. */
        const val MAX_PAGES = 20
    }
}
