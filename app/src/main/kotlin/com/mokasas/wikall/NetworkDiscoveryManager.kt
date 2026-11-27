package com.mokasas.wikall

import android.content.Context
import android.net.Network

/**
 * Serial DNS-SD discovery scoped to the already provisioned realm.
 * Discovered endpoints are untrusted hints, not evidence of membership.
 * EasyTier's network secret and the directory's pinned TLS key remain mandatory.
 * All lifecycle calls and callbacks are on the main thread.
 */
internal class NetworkDiscoveryManager(context: Context) {
    private val scanner = LanDiscovery(context, "_wikall-ol._udp.", OVERLAY_PORT, "WiKall")
    private var generation = 0L

    fun scan(
        pin: String,
        links: Set<Network?>,
        ownAddresses: Set<String>,
        reachable: (String) -> Boolean,
        onResult: (List<String>) -> Unit,
    ) {
        cancel()
        val round = generation
        val pending = ArrayDeque(links.take(MAX_LINKS))
        val found = linkedSetOf<String>()
        fun next() {
            if (round != generation) return
            if (pending.isEmpty() || found.size >= USER_LIMIT) {
                onResult(found.toList())
                return
            }
            val link = pending.removeFirst()
            scanner.findPeers(pin, ownAddresses, limit = USER_LIMIT - found.size, network = link,
                accept = { address, _ -> reachable(address) }) { peers ->
                if (round == generation) {
                    found.addAll(peers)
                    next()
                }
            }
        }
        next()
    }

    fun cancel() {
        generation++
        scanner.close()
    }

    private companion object {
        const val MAX_LINKS = 4
    }
}

/** Bounded candidate memory. One missed multicast scan must not restart a working engine. */
internal class OverlayPeerCache {
    private val seen = linkedMapOf<String, Long>()

    fun update(
        candidates: List<String>,
        ownAddresses: Set<String>,
        nowMs: Long,
        reachable: (String) -> Boolean,
    ): List<String> {
        fun eligible(address: String) =
            privateIpv4(address) && !overlayAddress(address) &&
                address !in ownAddresses && reachable(address)
        seen.entries.removeAll { (address, lastSeen) ->
            nowMs < lastSeen || nowMs - lastSeen >= TTL_MS || !eligible(address)
        }
        candidates.asSequence().distinct().filter(::eligible).forEach { address ->
            if (address in seen || seen.size < MAX_CANDIDATES) seen[address] = nowMs
        }
        return seen.keys.sorted()
    }

    fun clear() = seen.clear()

    private companion object {
        const val TTL_MS = 120_000L
        const val MAX_CANDIDATES = 12
    }
}
