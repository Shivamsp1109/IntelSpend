package com.spendwise.data.remote

data class UserProfilePayload(
    val uid: String,
    val name: String,
    val email: String,
    val gender: String?,
    val explicitProfileImageUrl: String?,
    val googlePhotoUrl: String?,
    val providers: List<String>,
    val updatedAt: Long
)
