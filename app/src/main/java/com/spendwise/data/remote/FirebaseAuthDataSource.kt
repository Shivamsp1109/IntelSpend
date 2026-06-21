package com.spendwise.data.remote

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.spendwise.domain.repository.Gender
import com.spendwise.domain.repository.AuthUser
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

class FirebaseAuthDataSource @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage
) {
    val currentUser: Flow<AuthUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            val user = auth.currentUser
            if (user == null) {
                trySend(null)
                return@AuthStateListener
            }
            firestore.collection(USERS_COLLECTION).document(user.uid).get()
                .addOnSuccessListener { doc ->
                    trySend(
                        AuthUser(
                            id = user.uid,
                            name = doc.getString("name") ?: user.displayName,
                            email = user.email,
                            gender = Gender.fromLabel(doc.getString("gender")),
                            photoUrl = user.photoUrl?.toString(),
                            providerIds = user.providerData.map { it.providerId }.filter { it != "firebase" },
                            explicitProfileImageUrl = doc.getString("explicitProfileImageUrl")
                        )
                    )
                }
                .addOnFailureListener {
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
        val doc = firestore.collection(USERS_COLLECTION).document(uid)
        val existing = doc.get().await()
        val existingExplicitImage = existing.getString("explicitProfileImageUrl")
        val existingProviders = existing.get("providers") as? List<*>
        val mergedProviders = (providers + existingProviders.orEmpty().filterIsInstance<String>())
            .distinct()

        val data = mutableMapOf<String, Any>(
            "uid" to uid,
            "name" to (name ?: existing.getString("name").orEmpty()),
            "email" to email.orEmpty(),
            "providers" to mergedProviders,
            "updatedAt" to System.currentTimeMillis()
        )
        gender?.let { data["gender"] = it.label }
        googlePhotoUrl?.let { data["googlePhotoUrl"] = it }
        (explicitProfileImageUrl ?: existingExplicitImage)?.let {
            data["explicitProfileImageUrl"] = it
        }

        doc.set(data, SetOptions.merge()).await()
    }

    private companion object {
        const val USERS_COLLECTION = "users"
    }
}
