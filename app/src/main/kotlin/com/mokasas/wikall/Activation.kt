package com.mokasas.wikall

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val HOST_NUMBER = "99000"
internal const val USER_LIMIT = 30
internal const val FILE_LIMIT = 12 * 1024
internal val userNumbers = (99001..99999).map(Int::toString)

internal data class DeviceRequest(val signing: PublicKey, val encryption: PublicKey) {
    val serial: String get() = Wire.pin(signing)

    fun export(key: PrivateKey): ByteArray {
        val body = buildJsonObject {
            put("type", "wi-call-request-1")
            put("serial", serial)
            put("signing", Wire.encode(signing.encoded))
            put("encryption", Wire.encode(encryption.encoded))
        }.bytes()
        return signed(body, key)
    }

    companion object {
        fun read(bytes: ByteArray): DeviceRequest {
            val envelope = json(bytes)
            val body = Wire.decode(envelope.value("body"))
            val request = json(body)
            require(request.value("type") == "wi-call-request-1")
            val key = publicKey(request.value("signing"))
            require(Wire.verified(key, body, Wire.decode(envelope.value("signature"))))
            require(request.value("serial") == Wire.pin(key))
            return DeviceRequest(key, publicKey(request.value("encryption")))
        }
    }
}

internal data class Enrollment(
    val number: String,
    val serial: String,
    val signing: String,
    val encryption: String,
    val activationId: String,
    val revoked: Boolean = false,
    val expiresAt: Long? = null,
    val activated: Boolean = false,
)

internal class CapacityReached : IllegalStateException("Wi-Call enrollment capacity reached")

// Revoked entries retain their number, so a saved contact never changes owner.
internal fun allocate(existing: List<Enrollment>, request: DeviceRequest, preferredNumber: String? = null): Enrollment {
    val previous = existing.firstOrNull { it.serial == request.serial }
    val signing = Wire.encode(request.signing.encoded)
    val encryption = Wire.encode(request.encryption.encoded)
    require(preferredNumber == null || preferredNumber in userNumbers)
    require(previous == null || preferredNumber == null || preferredNumber == previous.number)
    if (previous != null && !previous.revoked && previous.encryption == encryption &&
        (previous.expiresAt == null || previous.expiresAt > System.currentTimeMillis())
    ) return previous
    if (previous == null && existing.size >= USER_LIMIT) throw CapacityReached()
    val number = previous?.number ?: preferredNumber ?: userNumbers.first { candidate ->
        existing.none { it.number == candidate }
    }
    require(existing.none { it.number == number && it.serial != request.serial }) { "Number already assigned" }
    return Enrollment(number, request.serial, signing, encryption, Wire.token(), expiresAt =
        System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000)
}

internal data class Activation(
    val address: String?,
    val server: PublicKey,
    val number: String,
    val serial: String,
    val id: String,
    val overlaySecret: String? = null,
    val expiresAt: Long? = null,
    val overlayEpoch: Long = 0,
) {
    val pin: String get() = Wire.pin(server)

    fun canRefreshOverlay(previous: Activation): Boolean =
        pin == previous.pin && number == previous.number && serial == previous.serial &&
            id == previous.id && expiresAt == previous.expiresAt && overlaySecret != null &&
            (overlayEpoch > previous.overlayEpoch || previous.overlaySecret == null && overlayEpoch == previous.overlayEpoch)

    fun export(hostKey: PrivateKey, recipient: PublicKey): ByteArray {
        val body = buildJsonObject {
            put("type", "wi-call-activation-1")
            address?.let { put("address", it) }
            expiresAt?.let { put("expiresAt", it) }
            put("server", Wire.encode(server.encoded))
            put("serverAlgorithm", server.algorithm)
            put("number", number)
            put("serial", serial)
            put("id", id)
            overlaySecret?.let { put("overlaySecret", it) }
            if (overlayEpoch > 0) put("overlayEpoch", overlayEpoch)
        }.bytes()
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val payload = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, aes) }
        return buildJsonObject {
            put("type", "wi-call-encrypted-1")
            put("key", Wire.encode(rsa(Cipher.ENCRYPT_MODE, recipient).doFinal(aes.encoded)))
            put("iv", Wire.encode(payload.iv))
            put("data", Wire.encode(payload.doFinal(signed(body, hostKey))))
        }.bytes()
    }

    fun proof(challenge: String): ByteArray = activationProof(pin, number, id, challenge)

    companion object {
        fun read(bytes: ByteArray, decryption: PrivateKey, serial: String): Activation {
            val encrypted = json(bytes)
            require(encrypted.value("type") == "wi-call-encrypted-1")
            val key = SecretKeySpec(rsa(Cipher.DECRYPT_MODE, decryption).doFinal(Wire.decode(encrypted.value("key"))), "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Wire.decode(encrypted.value("iv"))))
            }
            val envelope = json(cipher.doFinal(Wire.decode(encrypted.value("data"))))
            val body = Wire.decode(envelope.value("body"))
            val decoded = json(body)
            val server = when (decoded.value("serverAlgorithm")) {
                "EC" -> KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Wire.decode(decoded.value("server"))))
                "RSA" -> publicKey(decoded.value("server"))
                else -> error("Unsupported server identity")
            }
            if (server is java.security.interfaces.ECPublicKey) require(server.params.curve.field.fieldSize == 256)
            require(Wire.verified(server, body, Wire.decode(envelope.value("signature"))))
            require(decoded.value("type") == "wi-call-activation-1")
            val address = decoded["address"]?.jsonPrimitive?.content?.takeIf(::privateIpv4)
            val expiresAt = decoded["expiresAt"]?.jsonPrimitive?.content?.let { it.toLong() }
            require(expiresAt == null || expiresAt > System.currentTimeMillis()) { "Activation expired" }
            val number = decoded.value("number")
            val id = decoded.value("id")
            require(number in userNumbers && decoded.value("serial") == serial && validNonce(id))
            val secret = decoded["overlaySecret"]?.jsonPrimitive?.content
            require(secret == null || validNonce(secret))
            val epoch = decoded["overlayEpoch"]?.jsonPrimitive?.content?.toLong() ?: 0L
            require(epoch >= 0 && (epoch == 0L || secret != null))
            return Activation(address, server, number, serial, id, secret, expiresAt, epoch)
        }
    }
}

internal fun discoveryId(pin: String): String =
    Wire.encode(MessageDigest.getInstance("SHA-256").digest("Wi-Call discovery 1\n$pin".encodeToByteArray()))

internal fun activationProof(pin: String, number: String, id: String, challenge: String): ByteArray {
    require(number in userNumbers && validNonce(id) && validNonce(challenge))
    return "Wi-Call device authentication 1\n$pin\n$number\n$id\n$challenge".encodeToByteArray()
}

internal fun resumeProof(pin: String, number: String, challenge: String): ByteArray {
    require(number in userNumbers && validNonce(challenge))
    return "WiKall activated device 1\n$pin\n$number\n$challenge".encodeToByteArray()
}

internal fun validNonce(value: String): Boolean = value.length == 32 &&
    value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

internal fun publicKey(encoded: String): PublicKey {
    val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Wire.decode(encoded)))
    require((key as java.security.interfaces.RSAPublicKey).modulus.bitLength() == 2048)
    return key
}

internal fun rsa(mode: Int, key: java.security.Key) = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").apply {
    init(mode, key, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
}

private fun signed(body: ByteArray, key: PrivateKey): ByteArray = buildJsonObject {
    put("body", Wire.encode(body))
    put("signature", Wire.encode(Wire.signature(key, body)))
}.bytes()

private fun JsonObject.bytes() = toString().encodeToByteArray()
private fun json(bytes: ByteArray): JsonObject {
    require(bytes.size in 1..FILE_LIMIT)
    return Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
}
private fun JsonObject.value(name: String) = getValue(name).jsonPrimitive.content
