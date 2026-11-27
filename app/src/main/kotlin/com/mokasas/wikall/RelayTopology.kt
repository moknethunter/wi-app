package com.mokasas.wikall

/** Native peer IDs are transient routing identifiers, never enrolled user identities. */
internal data class NativeRoute(
    val destinationPeer: Long,
    val nextHopPeer: Long,
    val address: String?,
    val cost: Int?,
)

internal data class RelayTopology(
    val directRoutes: Int,
    val relayedRoutes: Int,
    val intermediatePeers: Int,
)

/** Counts only unambiguous native routes. Missing or conflicting data is unknown. */
internal fun summarizeRelayTopology(routes: List<NativeRoute>?): RelayTopology? {
    if (routes == null) return null
    val byDestination = routes.groupBy { it.destinationPeer }
    if (byDestination.values.any { it.distinct().size != 1 }) return null
    val unique = routes.distinct()
    val relayed = unique.filter { it.destinationPeer != it.nextHopPeer }
    return RelayTopology(
        directRoutes = unique.size - relayed.size,
        relayedRoutes = relayed.size,
        intermediatePeers = relayed.map { it.nextHopPeer }.distinct().size,
    )
}
