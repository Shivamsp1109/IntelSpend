package com.spendwise.data.repository

import android.net.Uri
import com.spendwise.data.remote.FirebaseAuthDataSource
import com.spendwise.domain.repository.AuthRepository
import com.spendwise.domain.repository.AuthUser
import com.spendwise.domain.repository.Gender
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class AuthRepositoryImpl @Inject constructor(
    private val dataSource: FirebaseAuthDataSource
) : AuthRepository {
    override val currentUser: Flow<AuthUser?> = dataSource.currentUser

    override suspend fun loginWithEmail(email: String, password: String): Result<Unit> =
        runCatching { dataSource.loginWithEmail(email, password) }

    override suspend fun registerWithEmail(
        email: String,
        password: String,
        name: String,
        gender: Gender,
        profileImageUri: Uri?
    ): Result<Unit> =
        runCatching { dataSource.registerWithEmail(email, password, name, gender, profileImageUri) }

    override suspend fun loginWithGoogleIdToken(idToken: String): Result<Unit> =
        runCatching { dataSource.loginWithGoogleIdToken(idToken) }

    override suspend fun logout() {
        dataSource.logout()
    }
}
