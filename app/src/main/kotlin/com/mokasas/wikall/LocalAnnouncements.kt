package com.mokasas.wikall

import android.net.Network

/** Maintains a separate discovery announcement on each reachable local link. */
internal class LocalAnnouncements(private val discoveryFactory: () -> LanDiscovery) {
    private val active = mutableMapOf<Network?, LanDiscovery>()
    private var pin: String? = null

    fun update(networkPin: String, networks: Set<Network?>) {
        if (pin != networkPin) close()
        pin = networkPin
        (active.keys - networks).forEach { active.remove(it)?.close() }
        (networks - active.keys).forEach { network ->
            val discovery = discoveryFactory()
            discovery.advertise(networkPin, network)
            active[network] = discovery
        }
    }

    fun close() {
        active.values.forEach(LanDiscovery::close)
        active.clear()
        pin = null
    }
}
