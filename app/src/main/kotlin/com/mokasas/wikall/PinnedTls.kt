package com.mokasas.wikall

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal fun pinnedTrust(pin: String): X509TrustManager = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not used")
    }
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Missing server certificate")
        if (!Wire.equal(pin, Wire.pin(leaf.publicKey))) throw CertificateException("MokAsas fingerprint mismatch")
        leaf.checkValidity()
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal fun pinnedTls(trust: X509TrustManager): SSLContext = SSLContext.getInstance("TLS").apply {
    init(null, arrayOf(trust), null)
}
