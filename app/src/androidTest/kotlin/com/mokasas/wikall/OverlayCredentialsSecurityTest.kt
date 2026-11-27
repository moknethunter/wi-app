package com.mokasas.wikall

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OverlayCredentialsSecurityTest {
    @Test fun migratesLegacyCredentialAndRejectsTampering() {
        val name = "overlay-test-${UUID.randomUUID()}"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val original = Wire.token()
        try {
            assertTrue(prefs.edit().putString("secret", original).putLong("epoch", 4).commit())
            val credentials = OverlayCredentials(prefs, name)
            assertEquals(original to 4L, credentials.current())
            assertFalse(prefs.contains("secret"))
            assertEquals(original to 4L, OverlayCredentials(prefs, name).current())
            val ciphertext = checkNotNull(prefs.getString("secret_aes_gcm_v1", null))
            assertNotEquals(original, ciphertext)
            val bytes = Base64.decode(ciphertext, Base64.NO_WRAP)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            assertTrue(prefs.edit().putString("secret_aes_gcm_v1", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
            assertTrue(runCatching { credentials.current() }.isFailure)
        } finally {
            context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(name) }
        }
    }

    @Test fun rotatesCredentialAndPreservesEpoch() {
        val name = "overlay-test-${UUID.randomUUID()}"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val credentials = OverlayCredentials(prefs, name)
            val first = credentials.current()
            assertTrue(credentials.revokeDevice {})
            val second = OverlayCredentials(prefs, name).current()
            assertNotEquals(first.first, second.first)
            assertEquals(first.second + 1, second.second)
            assertFalse(prefs.contains("secret"))
            assertFalse(prefs.getBoolean("rekeyPending", false))
        } finally {
            context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(name) }
        }
    }
}
