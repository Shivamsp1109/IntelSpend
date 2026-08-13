package com.spendwise.util.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-GCM encryption under keys held in the device's hardware keystore.
 *
 * The keys never exist as bytes this process can read — encryption happens
 * inside the keystore and, on devices with a secure element, inside dedicated
 * hardware. That is the property worth having: an attacker who copies the app's
 * files off the device gets ciphertext and no key, because the key cannot be
 * copied even by us.
 *
 * The consequence, which matters for backups, is that a key does not survive a
 * move to different hardware. Anything encrypted with it is excluded from Auto
 * Backup for that reason — see res/xml/backup_rules.xml.
 *
 * GCM is authenticated, so tampering with a stored file makes decryption throw
 * rather than quietly returning wrong bytes.
 */
@Singleton
class KeystoreCrypto @Inject constructor() {

    /** Returns `iv || ciphertext`, self-contained so callers store one blob. */
    fun encrypt(alias: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyFor(alias))

        // The keystore generates the IV itself; supplying our own is rejected
        // outright, which is the API stopping us from ever reusing one.
        val iv = cipher.iv
        require(iv.size == IV_LENGTH) { "Unexpected GCM IV length: ${iv.size}" }

        return iv + cipher.doFinal(plaintext)
    }

    /**
     * Reverses [encrypt]. Throws if the payload was altered, truncated, or
     * written under a key this device no longer holds.
     *
     * Deliberately buffered rather than streamed: `CipherInputStream` has a long
     * history of dropping the authentication failure on the floor and returning
     * a short read instead, so a tampered file could come back looking merely
     * empty. `doFinal` raises it properly.
     */
    fun decrypt(alias: String, payload: ByteArray): ByteArray {
        require(payload.size > IV_LENGTH) { "Payload is too short to contain an IV." }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyFor(alias),
            GCMParameterSpec(TAG_LENGTH_BITS, payload, 0, IV_LENGTH)
        )
        return cipher.doFinal(payload, IV_LENGTH, payload.size - IV_LENGTH)
    }

    /** Drops a key, making everything encrypted under it permanently unreadable. */
    fun deleteKey(alias: String) {
        keyStore().deleteEntry(alias)
    }

    private fun keyFor(alias: String): SecretKey {
        val store = keyStore()
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // No biometric gate: background sync and scheduled work need to
                // read the database, and they cannot prompt anyone.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(PROVIDER).apply { load(null) }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }
}
