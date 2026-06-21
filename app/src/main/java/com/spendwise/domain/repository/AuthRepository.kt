package com.spendwise.domain.repository

import android.net.Uri
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val currentUser: Flow<AuthUser?>
    suspend fun loginWithEmail(email: String, password: String): Result<Unit>
    suspend fun registerWithEmail(
        email: String,
        password: String,
        name: String,
        gender: Gender,
        profileImageUri: Uri?
    ): Result<Unit>
    suspend fun loginWithGoogleIdToken(idToken: String): Result<Unit>
    suspend fun logout()
}

data class AuthUser(
    val id: String,
    val name: String?,
    val email: String?,
    val gender: Gender? = null,
    val photoUrl: String? = null,
    val providerIds: List<String> = emptyList(),
    val explicitProfileImageUrl: String? = null
) {
    val isGoogleUser: Boolean = providerIds.contains(AuthProvider.Google.providerId)
    val isEmailPasswordUser: Boolean = providerIds.contains(AuthProvider.EmailPassword.providerId)
    val displayPhotoUrl: String? = explicitProfileImageUrl ?: photoUrl
}

enum class Gender(val label: String) {
    Male("Male"),
    Female("Female"),
    NonBinary("Non-binary");

    companion object {
        fun fromLabel(label: String?): Gender? =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
    }
}

enum class AuthProvider(val providerId: String) {
    Google("google.com"),
    EmailPassword("password")
}
