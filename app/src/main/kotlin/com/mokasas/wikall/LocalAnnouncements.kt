package com.mokasas.wikall

import android.net.Network

/** Separate advertisements per link; only NSD registration callbacks establish publication. */
internal class LocalAnnouncements(
    private val onPublishedCount: (Int) -> Unit = {},
    private val discoveryFactory: () -> LanDiscovery,
) {
    private val active = mutableMapOf<Network?, LanDiscovery>()
    private val published = mutableSetOf<Network?>()
    private val failed = mutableSetOf<Network?>()
    private var pin: String? = null

    fun update(networkPin: String, networks: Set<Network?>) {
        if (pin != networkPin) close()
        pin = networkPin
        val removed = (active.keys - networks) + failed.toSet()
        removed.forEach {
            active.remove(it)?.close()
            published.remove(it)
            failed.remove(it)
        }
        onPublishedCount(published.size)
        (networks - active.keys).forEach { network ->
            val discovery = discoveryFactory()
            active[network] = discovery
            discovery.advertise(networkPin, network, onPublished = { ok ->
                if (active[network] === discovery) {
                    if (ok) {
                        published.add(network)
                        failed.remove(network)
                    } else {
                        published.remove(network)
                        failed.add(network)
                    }
                    onPublishedCount(published.size)
                }
            })
        }
    }

    fun close() {
        val old = active.values.toList()
        active.clear()
        published.clear()
        failed.clear()
        pin = null
        old.forEach(LanDiscovery::close)
        onPublishedCount(0)
    }
}
