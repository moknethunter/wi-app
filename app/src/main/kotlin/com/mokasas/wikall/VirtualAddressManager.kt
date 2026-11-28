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

/** Routes provide possible endpoints. TLS must still authenticate the saved activation authority. */
internal fun overlayDirectoryCandidates(
    routes: List<NativeRoute>?,
    localAddress: String,
    existing: String?,
    round: Int = 0,
): List<String> {
    val reported = routes.orEmpty().groupBy { it.destinationPeer }.values.asSequence()
        .mapNotNull { observations -> observations.distinct().singleOrNull() }
        .filter { it.cost != null && it.cost > 0 }
        .sortedBy { it.cost }
        .mapNotNull { it.address }
        .filter { it != OVERLAY_NETWORK && it != "10.126.127.255" && overlayAddress(it) }
    val legacy = listOf(OVERLAY_HOST).filter { it != localAddress && it != existing }
    val alternatives = reported.distinct()
        .filter { it != localAddress && it != existing && it != OVERLAY_HOST }
        .toList()
    if (alternatives.isEmpty()) return legacy

    val budget = 4 - legacy.size
    val start = (Math.floorMod(round, alternatives.size) * budget) % alternatives.size
    return legacy + List(minOf(budget, alternatives.size)) { index ->
        alternatives[(start + index) % alternatives.size]
    }
}
