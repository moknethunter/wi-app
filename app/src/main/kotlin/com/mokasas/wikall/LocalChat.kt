package com.mokasas.wikall

import java.security.PrivateKey
import java.security.PublicKey
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal const val CHAT_TEXT_BYTES = 2048
internal val chatProbe = UUID(0, 4)

internal data class ChatAddress(val realm: String, val from: String, val to: String, val id: UUID) {
    fun associatedData() = "WiKall local chat 1\n$realm\n$from\n$to\n$id".encodeToByteArray()
}

internal object ChatEnvelope {
    fun seal(address: ChatAddress, text: String, recipient: PublicKey, signing: PrivateKey): String {
        val message = text.encodeToByteArray()
        require(text.isNotBlank() && message.size <= CHAT_TEXT_BYTES)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(address.associatedData())
        }
        val encrypted = listOf(cipher.iv, rsa(Cipher.ENCRYPT_MODE, recipient).doFinal(key.encoded), cipher.doFinal(message))
            .joinToString(".", transform = Wire::encode)
        return "$encrypted.${Wire.encode(Wire.signature(signing, authenticated(address, encrypted)))}"
    }

    fun verify(address: ChatAddress, envelope: String, signing: PublicKey): Boolean = runCatching {
        val parsed = envelope.split('.', limit = 5)
        require(parsed.size == 4 && envelope.length <= 4096)
        require(Wire.decode(parsed[0]).size == 12 && Wire.decode(parsed[1]).size == 256)
        require(Wire.decode(parsed[2]).size in 17..CHAT_TEXT_BYTES + 16)
        Wire.verified(signing, authenticated(address, envelope.substringBeforeLast('.')), Wire.decode(parsed[3]))
    }.getOrDefault(false)

    fun open(address: ChatAddress, envelope: String, signing: PublicKey, recipient: PrivateKey): String {
        require(verify(address, envelope, signing))
        val (iv, wrapped, encrypted) = envelope.split('.', limit = 4)
        val key = SecretKeySpec(rsa(Cipher.DECRYPT_MODE, recipient).doFinal(Wire.decode(wrapped)), "AES")
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Wire.decode(iv)))
            updateAAD(address.associatedData())
            doFinal(Wire.decode(encrypted)).decodeToString(throwOnInvalidSequence = true)
        }
    }

    private fun authenticated(address: ChatAddress, encrypted: String) =
        address.associatedData() + '\n'.code.toByte() + encrypted.encodeToByteArray()
}

/** Stores the opaque recipient envelope until the authenticated recipient acknowledges it. */
internal class LocalChatRelay(
    private val realm: String,
    private val registry: EnrollmentRegistry,
    private val storage: ChatLedger,
    private val masterKeys: DeviceRequest,
) {
    fun pending(number: String): List<RoutedSignal> = storage.pendingChat(realm, number).map(::delivery)

    fun route(from: String, signal: Signal): List<RoutedSignal> {
        val decline = RoutedSignal(HOST_NUMBER, from, Signal(SignalType.CHAT_DECLINED, signal.id))
        return when (signal.type) {
            SignalType.CHAT_KEY_REQUEST -> {
                val to = checkNotNull(signal.number)
                val target = if (to != from) keys(to) else null
                val reply = target?.let { Signal(SignalType.CHAT_KEY, signal.id, Wire.encode(it.encryption.encoded), to) }
                listOf(if (reply == null) decline else RoutedSignal(HOST_NUMBER, from, reply))
            }
            SignalType.CHAT_SEND -> {
                val to = checkNotNull(signal.number)
                val sender = keys(from) ?: return listOf(decline)
                if (to == from || keys(to) == null) return listOf(decline)
                val envelope = checkNotNull(signal.payload)
                if (!ChatEnvelope.verify(ChatAddress(realm, from, to, signal.id), envelope, sender.signing)) return listOf(decline)
                val packet = ChatPacket(realm, to, from, signal.id.toString(), Wire.encode(sender.signing.encoded), envelope,
                    System.currentTimeMillis())
                when (storage.queueChat(packet)) {
                    ChatQueueResult.NEW -> listOf(RoutedSignal(HOST_NUMBER, from, Signal(SignalType.CHAT_ACCEPTED, signal.id)), delivery(packet))
                    ChatQueueResult.DUPLICATE -> listOf(RoutedSignal(HOST_NUMBER, from, Signal(SignalType.CHAT_ACCEPTED, signal.id)))
                    ChatQueueResult.REJECTED -> listOf(decline)
                }
            }
            SignalType.CHAT_ACK -> {
                storage.ackChat(realm, from, checkNotNull(signal.number), signal.id.toString())
                emptyList()
            }
            else -> emptyList()
        }
    }

    private fun keys(number: String): DeviceRequest? {
        if (number == HOST_NUMBER) return masterKeys
        val user = registry.find(number) ?: return null
        if (user.revoked || user.expiresAt?.let { it <= System.currentTimeMillis() } == true) return null
        return DeviceRequest(publicKey(user.signing), publicKey(user.encryption))
    }

    private fun delivery(packet: ChatPacket) = RoutedSignal(packet.sender, packet.recipient,
        Signal(SignalType.CHAT_DELIVERY, UUID.fromString(packet.id), "${packet.signing}.${packet.envelope}", packet.sender))
}
