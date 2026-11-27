package com.mokasas.wikall

/** A verified native address and the precise route to install in Android's TUN. */
internal data class VirtualAddressPlan(val address: String, val prefix: Int, val network: String)

/** Transitional policy: accept only the seeded realm until dynamic realm discovery is implemented. */
internal fun nativeAddressPlan(snapshot: EasyTierSnapshot?, request: OverlayRequest): VirtualAddressPlan? {
    if (snapshot?.running != true) return null
    val address = snapshot.address ?: return null
    val prefix = snapshot.prefix ?: return null
    if (prefix != OVERLAY_PREFIX || !overlayAddress(address)) return null
    if (request.seedAddress != null && address != request.seedAddress) return null
    if (request.seedAddress == null && address == OVERLAY_HOST) return null
    val octets = address.split('.').map(String::toInt)
    val value = octets.fold(0L) { acc, octet -> (acc shl 8) or octet.toLong() }
    val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
    val network = value and mask
    val broadcast = network or (mask xor 0xffffffffL)
    if (value == network || value == broadcast) return null
    val networkAddress = listOf(24, 16, 8, 0).joinToString(".") { shift -> ((network shr shift) and 255).toString() }
    if (networkAddress != OVERLAY_NETWORK) return null
    return VirtualAddressPlan(address, prefix, networkAddress)
}
