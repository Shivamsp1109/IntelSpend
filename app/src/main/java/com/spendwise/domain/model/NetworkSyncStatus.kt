package com.spendwise.domain.model

data class NetworkSyncStatus(
    val isOnline: Boolean,
    val pendingSyncCount: Int
) {
    val label: String
        get() = when {
            !isOnline -> "Offline"
            pendingSyncCount > 0 -> "Sync pending"
            else -> "Online"
        }
}
