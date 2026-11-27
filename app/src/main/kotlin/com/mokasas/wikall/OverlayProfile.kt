package com.mokasas.wikall

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val OVERLAY_PORT = 11010
internal const val OVERLAY_NETWORK = "10.126.124.0"
internal const val OVERLAY_PREFIX = 22
internal const val OVERLAY_HOST = "10.126.124.1"
internal const val OVERLAY_INSTANCE = "wikall"

internal data class OverlayRequest(
    val pin: String,
    val secret: String,
    val number: String,
    val peer: String?,
    val extraPeers: List<String> = emptyList(),
    val listenerAddresses: List<String> = emptyList(),
    val transportRevision: Long = 0,
) {
    init {
        require(pin.length == 64 && pin.all { it in '0'..'9' || it in 'A'..'F' })
        require(validNonce(secret))
        require(validNumber(number))
        require((listOfNotNull(peer) + extraPeers + listenerAddresses).all { privateIpv4(it) && !overlayAddress(it) })
    }

    // Transitional seed: EasyTier v2.6.4 does not assign DHCP addresses without peers.
    // Only the directory owner seeds the current realm. Clients never derive IP from number.
    val seedAddress: String? get() = OVERLAY_HOST.takeIf { number == HOST_NUMBER }

    fun canReuseTunnelFor(next: OverlayRequest): Boolean =
        pin == next.pin && number == next.number && secret == next.secret &&
            transportRevision == next.transportRevision && listenerAddresses == next.listenerAddresses

    // The configuration carries a realm secret. Never expose it via generated data-class toString.
    override fun toString(): String = "OverlayRequest(redacted)"

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
