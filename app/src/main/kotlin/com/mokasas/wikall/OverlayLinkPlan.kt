package com.mokasas.wikall

/** Select only configured local listeners and currently reachable physical peers. */
internal class OverlayLinkPlan private constructor(
    val listeners: List<String>,
    val preferredPeer: String?,
    val otherPeers: List<String>,
) {
    companion object {
        fun select(
            listeners: List<String>,
            preferredPeer: String?,
            discoveredPeers: List<String>,
            reachable: (String) -> Boolean,
        ): OverlayLinkPlan? {
            if (listeners.isEmpty()) return null
            val selected = preferredPeer?.takeIf(reachable)
            return OverlayLinkPlan(
                listeners.distinct().sorted(),
                selected,
                discoveredPeers.distinct().filter { it != selected && reachable(it) }.sorted(),
            )
        }
    }
}
