package com.mokasas.wikall

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val OVERLAY_PORT = 11010
internal const val OVERLAY_NETWORK = "10.126.124.0"
internal const val OVERLAY_PREFIX = 22
internal const val OVERLAY_HOST = "10.126.124.1"
internal const val OVERLAY_INSTANCE = "wikall"

internal data class OverlayRequest(val pin: String, val secret: String, val number: String, val peer: String?, val extraPeers: List<String> = emptyList(), val listenerAddresses: List<String> = emptyList()) {
    init {
        require(pin.length == 64 && pin.all { it in '0'..'9' || it in 'A'..'F' })
        require(validNonce(secret))
        require(validNumber(number))
        require((listOfNotNull(peer) + extraPeers + listenerAddresses).all { privateIpv4(it) && !overlayAddress(it) })
    }

    val address: String
        get() {
            val offset = number.toInt() - HOST_NUMBER.toInt() + 1
            return "10.126.${124 + offset / 256}.${offset % 256}"
        }

    fun configuration() = buildString {
        appendLine("inst_name = \"$OVERLAY_INSTANCE\"")
        appendLine("ipv4 = \"$address/$OVERLAY_PREFIX\"")
        val listeners = listenerAddresses.ifEmpty { listOf("0.0.0.0") }
            .joinToString(", ") { "\"udp://$it:$OVERLAY_PORT\"" }
        appendLine("listeners = [$listeners]")
        appendLine("stun_servers = []")
        appendLine("stun_servers_v6 = []")
        appendLine("[network_identity]")
        appendLine("network_name = \"WiKall-${pin.take(24)}\"")
        appendLine("network_secret = \"$secret\"")
        appendLine("[flags]")
        appendLine("private_mode = true")
        appendLine("enable_exit_node = false")
        appendLine("enable_ipv6 = false")
        appendLine("accept_dns = false")
        appendLine("disable_udp_hole_punching = true")
        appendLine("disable_tcp_hole_punching = true")
        (listOfNotNull(peer) + extraPeers).distinct().forEach { endpoint ->
            appendLine("[[peer]]")
            appendLine("uri = \"udp://$endpoint:$OVERLAY_PORT\"")
        }
    }
}

internal fun overlayAddress(address: String): Boolean {
    if (!privateIpv4(address)) return false
    val parts = address.split('.')
    return parts[0] == "10" && parts[1] == "126" && parts[2].toInt() in 124..127
}

internal fun newOverlaySecret(passphrase: String): String {
    require(passphrase.length in 12..128 && passphrase.isNotBlank())
    val random = Wire.decode(Wire.token())
    val key = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(random, "HmacSHA256"))
    }
    return Wire.encode(key.doFinal(passphrase.encodeToByteArray()).copyOf(24))
}
