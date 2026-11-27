package com.mokasas.wikall

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the Master group secret under an Android Keystore AES key. */
internal class OverlayCredentials(
    private val preferences: SharedPreferences,
    private val alias: String = "wikall.overlay.credentials.v1",
) {
    private val revocation = Any()

    private fun key(create: Boolean): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return@synchronized it }
        check(create) { "Overlay encryption key unavailable" }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun seal(secret: String): String {
        require(validNonce(secret))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(AAD)
        val iv = cipher.iv
        check(iv.size == 12)
        return Base64.encodeToString(iv + cipher.doFinal(secret.encodeToByteArray()), Base64.NO_WRAP)
    }

    private fun unseal(value: String): String {
        val data = Base64.decode(value, Base64.NO_WRAP)
        require(data.size == 12 + 32 + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        cipher.updateAAD(AAD)
        return cipher.doFinal(data.copyOfRange(12, data.size)).decodeToString().also { require(validNonce(it)) }
    }

    fun current(): Pair<String, Long> = synchronized(preferences) {
        val encrypted = preferences.getString(SEALED, null)
        val secret = if (encrypted != null) {
            // Never replace an unreadable credential with a new network silently.
            unseal(encrypted).also {
                if (preferences.contains(LEGACY)) check(preferences.edit().remove(LEGACY).commit())
            }
        } else {
            val existing = preferences.getString(LEGACY, null) ?: Wire.token()
            check(preferences.edit().putString(SEALED, seal(existing)).remove(LEGACY).commit())
            existing
        }
        secret to preferences.getLong("epoch", 0)
    }

    fun recoverInterruptedRevocation() = synchronized(revocation) {
        if (preferences.getBoolean("rekeyPending", false)) rotate()
    }

    fun revokeDevice(revoke: () -> Unit): Boolean = synchronized(revocation) {
        check(preferences.edit().putBoolean("rekeyPending", true).commit())
        revoke()
        runCatching { rotate() }.isSuccess
    }

    private fun rotate() = synchronized(preferences) {
        // Verify the existing key before replacing a credential.
        current()
        val nextEpoch = Math.addExact(preferences.getLong("epoch", 0), 1)
        check(preferences.edit().putString(SEALED, seal(Wire.token()))
            .remove(LEGACY).putLong("epoch", nextEpoch).remove("rekeyPending").commit())
    }

    private companion object {
        val KEY_LOCK = Any()
        val AAD = "WiKall/overlay-secret/v1".encodeToByteArray()
        const val SEALED = "secret_aes_gcm_v1"
        const val LEGACY = "secret"
    }
}
