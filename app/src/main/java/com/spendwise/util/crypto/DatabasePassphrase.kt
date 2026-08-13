package com.spendwise.util.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The passphrase protecting the local database.
 *
 * Generated once from the system's cryptographic random source, then stored
 * wrapped by a hardware-backed keystore key. Neither half is useful alone: the
 * stored blob is ciphertext, and the key that unwraps it cannot be extracted
 * from the device. So a copy of the app's data directory — pulled over ADB,
 * lifted from a rooted phone, or restored from a backup — yields nothing.
 *
 * A hardcoded or derived-from-device-id passphrase would defeat the whole
 * exercise: it would be the same on every install, or reconstructible by
 * anyone holding the files.
 */
@Singleton
class DatabasePassphrase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreCrypto: KeystoreCrypto
) {
    private val preferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The passphrase as text, created on first call.
     *
     * Base64 of 32 random bytes, so it is printable ASCII. That matters: the
     * database is opened two different ways — through SQLCipher's open helper,
     * and through an `ATTACH ... KEY ?` statement during the one-time migration
     * — and both must derive the same key. Keeping it text means both paths run
     * SQLCipher's usual key derivation over identical bytes. A raw binary
     * passphrase would be treated as a pre-derived key by one path and run
     * through the KDF by the other, and the two would not match.
     */
    /**
     * True when a stored passphrase existed but could no longer be unwrapped, so
     * a replacement was issued. See [asText].
     */
    @Volatile
    var previousKeyLost: Boolean = false
        private set

    @Synchronized
    fun asText(): String {
        preferences.getString(KEY_WRAPPED, null)?.let { stored ->
            runCatching {
                String(
                    keystoreCrypto.decrypt(KEY_ALIAS, Base64.decode(stored, Base64.NO_WRAP)),
                    Charsets.US_ASCII
                )
            }.onSuccess { return it }
                .onFailure { error ->
                    // A keystore key can genuinely disappear — some devices drop
                    // entries across an OS upgrade. Letting this propagate would
                    // fail every attempt to open the database, on every launch,
                    // for good: the app would be bricked until reinstalled, and
                    // reinstalling is exactly what discards the data anyway.
                    //
                    // So a new passphrase is issued and the caller is told the
                    // old one is gone, which lets it move the now-unreadable
                    // database aside and carry on. The local history is lost
                    // either way — nothing can decrypt it — but the app works
                    // and synced transactions come back.
                    Log.e(TAG, "Database passphrase could not be unwrapped; issuing a new one.", error)
                    previousKeyLost = true
                    runCatching { keystoreCrypto.deleteKey(KEY_ALIAS) }
                }
        }

        val random = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        val passphrase = Base64.encodeToString(random, Base64.NO_WRAP)

        preferences.edit()
            .putString(
                KEY_WRAPPED,
                Base64.encodeToString(
                    keystoreCrypto.encrypt(KEY_ALIAS, passphrase.toByteArray(Charsets.US_ASCII)),
                    Base64.NO_WRAP
                )
            )
            .commit() // Written synchronously: losing this loses the database.

        return passphrase
    }

    /**
     * A fresh copy for SQLCipher, which zeroes the array it is given. Each
     * connection needs its own; a shared one would come back blank.
     */
    fun asBytes(): ByteArray = asText().toByteArray(Charsets.US_ASCII)

    private companion object {
        const val TAG = "DatabasePassphrase"

        /** Excluded from backup — see res/xml/backup_rules.xml. */
        const val PREFS_NAME = "spendwise_secure_prefs"
        const val KEY_WRAPPED = "wrapped_db_passphrase"
        const val KEY_ALIAS = "spendwise_db_key"
        const val PASSPHRASE_BYTES = 32
    }
}
