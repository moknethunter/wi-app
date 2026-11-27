package com.mokasas.wikall

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

internal class ServerIdentity(val entry: KeyStore.PrivateKeyEntry) {
    fun tls(): SSLContext = SSLContext.getInstance("TLS").apply {
        val manager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: java.net.Socket?) = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) =
                if (keyType == entry.privateKey.algorithm) arrayOf(ALIAS) else null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: java.net.Socket?) =
                if (keyType == entry.privateKey.algorithm) ALIAS else null
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) =
                if (keyType == entry.privateKey.algorithm) ALIAS else null
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == ALIAS) entry.certificateChain.map { it as X509Certificate }.toTypedArray() else null
            override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) entry.privateKey else null
        }
        init(arrayOf<KeyManager>(manager), null, null)
    }

    companion object {
        private const val ALIAS = "wi-call-server-ec-v1"
        fun load(): ServerIdentity {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!store.containsAlias(ALIAS)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        // The platform TLS stack may pass a digest it has already computed.
                        .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                        .build())
                }.generateKeyPair()
            }
            return ServerIdentity(store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry)
        }
    }
}

internal class DeviceIdentity private constructor(
    val request: DeviceRequest,
    val signing: PrivateKey,
    val decryption: PrivateKey,
) {
    companion object {
        @Synchronized
        fun load(): DeviceIdentity {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val signer = entry(store, "wi-call-device-sign-v1", KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY) {
                setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            }
            val recipient = entry(store, "wi-call-device-decrypt-v1", KeyProperties.PURPOSE_DECRYPT) {
                setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            }
            return DeviceIdentity(
                DeviceRequest(signer.certificate.publicKey, recipient.certificate.publicKey),
                signer.privateKey, recipient.privateKey,
            )
        }

        private fun entry(
            store: KeyStore, alias: String, purpose: Int, configure: KeyGenParameterSpec.Builder.() -> Unit,
        ): KeyStore.PrivateKeyEntry {
            if (!store.containsAlias(alias)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                    initialize(KeyGenParameterSpec.Builder(alias, purpose)
                        .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                        .apply(configure).build())
                }.generateKeyPair()
            }
            return store.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        }
    }
}
