package com.mokasas.wikall

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.util.Base64
internal const val PORT = 8787

internal object Wire {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
    fun token(): String = encode(ByteArray(24).also(SecureRandom()::nextBytes))
    fun pin(key: PublicKey): String = MessageDigest.getInstance("SHA-256")
        .digest(key.encoded).joinToString("") { "%02X".format(it.toInt() and 255) }
    fun equal(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    fun signature(key: PrivateKey, body: ByteArray): ByteArray = Signature.getInstance(signatureAlgorithm(key.algorithm)).run {
        initSign(key)
        update(body)
        sign()
    }
    fun verified(key: PublicKey, body: ByteArray, signature: ByteArray): Boolean =
        Signature.getInstance(signatureAlgorithm(key.algorithm)).run {
            initVerify(key)
            update(body)
            verify(signature)
        }

    private fun signatureAlgorithm(key: String) = when (key) {
        "RSA" -> "SHA256withRSA"
        "EC" -> "SHA256withECDSA"
        else -> throw IllegalArgumentException("Unsupported signing key")
    }
}

internal fun privateIpv4(address: String): Boolean {
    val parts = address.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { part ->
        part.toIntOrNull()?.takeIf { it in 0..255 && it.toString() == part } ?: return false
    }
    val (first, second) = octets
    return first == 10 || first == 172 && second in 16..31 ||
        first == 192 && second == 168 || first == 169 && second == 254
}

// The activation's address is a hint. Every candidate still uses the pinned server key.
internal fun lanCandidates(discovered: String?, gateways: List<String>, previous: String?, issued: String?): List<String> =
    (listOfNotNull(discovered) + gateways + listOfNotNull(previous, issued))
        .filter(::privateIpv4)
        .distinct()

internal fun sameIpv4Subnet(first: ByteArray, second: ByteArray, prefix: Int): Boolean {
    require(first.size == 4 && second.size == 4 && prefix in 0..32)
    return first.indices.all { index ->
        val bits = (prefix - index * 8).coerceIn(0, 8)
        val mask = (0xff shl (8 - bits)) and 0xff
        (first[index].toInt() xor second[index].toInt()) and mask == 0
    }
}
