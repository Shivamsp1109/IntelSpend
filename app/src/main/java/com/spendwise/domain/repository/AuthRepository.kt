package com.spendwise.domain.repository

import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    val currentUser: Flow<AuthUser?>
    suspend fun loginWithEmail(email: String, password: String): Result<Unit>
    suspend fun registerWithEmail(email: String, password: String): Result<Unit>
    suspend fun loginWithGoogleIdToken(idToken: String): Result<Unit>
    suspend fun logout()
}

data class AuthUser(
    val id: String,
    val name: String?,
    val email: String?
)
