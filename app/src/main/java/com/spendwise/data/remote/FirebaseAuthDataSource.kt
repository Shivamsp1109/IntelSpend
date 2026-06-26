package com.spendwise.data.remote

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.storage.FirebaseStorage
import com.spendwise.domain.repository.Gender
import com.spendwise.domain.repository.AuthUser
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

class FirebaseAuthDataSource @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val storage: FirebaseStorage,
    private val userDataSource: MySqlUserDataSource
) {
    val currentUser: Flow<AuthUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            val user = auth.currentUser
            if (user == null) {
                trySend(null)
                return@AuthStateListener
            }
            launch {
                runCatching { userDataSource.getCurrentUserProfile() }
                    .onSuccess { profile ->
                        trySend(
                            AuthUser(
                                id = user.uid,
                                name = profile?.name?.takeIf { it.isNotBlank() } ?: user.displayName,
                                email = user.email,
                                gender = Gender.fromLabel(profile?.gender),
                                photoUrl = user.photoUrl?.toString(),
                                providerIds = user.providerData.map { it.providerId }.filter { it != "firebase" },
                                explicitProfileImageUrl = profile?.explicitProfileImageUrl
                            )
                        )
                    }.onFailure {
                        trySend(
                            AuthUser(
                                id = user.uid,
                                name = user.displayName,
                                email = user.email,
                                photoUrl = user.photoUrl?.toString(),
                                providerIds = user.providerData.map { it.providerId }.filter { it != "firebase" }
                            )
                        )
                    }
            }
        }
        firebaseAuth.addAuthStateListener(listener)
        awaitClose { firebaseAuth.removeAuthStateListener(listener) }
    }

    suspend fun loginWithEmail(email: String, password: String) {
        firebaseAuth.signInWithEmailAndPassword(email, password).await()
    }

    suspend fun registerWithEmail(
        email: String,
        password: String,
        name: String,
        gender: Gender,
        profileImageUri: Uri?
    ) {
        val result = firebaseAuth.createUserWithEmailAndPassword(email, password).await()
        val user = result.user ?: return
        val explicitProfileImageUrl = profileImageUri?.let { uploadProfileImage(user.uid, it) }

        val profileUpdates = UserProfileChangeRequest.Builder()
            .setDisplayName(name)
            .apply {
                explicitProfileImageUrl?.let { setPhotoUri(Uri.parse(it)) }
            }
            .build()
        user.updateProfile(profileUpdates).await()

        upsertUserProfile(
            uid = user.uid,
            name = name,
            email = email,
            gender = gender,
            explicitProfileImageUrl = explicitProfileImageUrl,
            googlePhotoUrl = null,
            providers = user.providerData.map { it.providerId }.filter { it != "firebase" }
        )
    }

    suspend fun loginWithGoogleIdToken(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val result = firebaseAuth.signInWithCredential(credential).await()
        val user = result.user ?: return
        upsertUserProfile(
            uid = user.uid,
            name = user.displayName,
            email = user.email,
            gender = null,
            explicitProfileImageUrl = null,
            googlePhotoUrl = user.photoUrl?.toString(),
            providers = user.providerData.map { it.providerId }.filter { it != "firebase" }
        )
    }

    fun logout() {
        firebaseAuth.signOut()
    }

    private suspend fun uploadProfileImage(uid: String, uri: Uri): String {
        val ref = storage.reference.child("profile_images/$uid/profile.jpg")
        ref.putFile(uri).await()
        return ref.downloadUrl.await().toString()
    }

    private suspend fun upsertUserProfile(
        uid: String,
        name: String?,
        email: String?,
        gender: Gender?,
        explicitProfileImageUrl: String?,
        googlePhotoUrl: String?,
        providers: List<String>
    ) {
        val existing = runCatching { userDataSource.getCurrentUserProfile() }.getOrNull()
        val mergedProviders = (providers + existing?.providers.orEmpty())
            .distinct()

        val data = UserProfilePayload(
            uid = uid,
            name = name ?: existing?.name.orEmpty(),
            email = email ?: existing?.email.orEmpty(),
            gender = gender?.label ?: existing?.gender,
            explicitProfileImageUrl = explicitProfileImageUrl ?: existing?.explicitProfileImageUrl,
            googlePhotoUrl = googlePhotoUrl ?: existing?.googlePhotoUrl,
            providers = mergedProviders,
            updatedAt = System.currentTimeMillis()
        )
        userDataSource.upsertCurrentUserProfile(data)
    }
}
