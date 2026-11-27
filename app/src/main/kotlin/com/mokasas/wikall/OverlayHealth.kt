package com.mokasas.wikall

/** Diagnostics only: a running instance or reported peers do not establish end-to-end reachability. */
internal enum class OverlayHealthState { UNKNOWN, STOPPED, TUN_MISMATCH, NO_PEERS, PEERS_REPORTED, RUNNING }

internal data class OverlayHealth(
    val state: OverlayHealthState,
    val peers: Int? = null,
    val routes: Int? = null,
)

internal fun assessOverlayHealth(snapshot: EasyTierSnapshot?, address: String, prefix: Int): OverlayHealth {
    if (snapshot?.running == null) return OverlayHealth(OverlayHealthState.UNKNOWN)
    if (!snapshot.running) return OverlayHealth(OverlayHealthState.STOPPED)
    if (snapshot.address == null || snapshot.prefix == null) return OverlayHealth(OverlayHealthState.UNKNOWN)
    if (snapshot.address != address || snapshot.prefix != prefix) return OverlayHealth(OverlayHealthState.TUN_MISMATCH)
    val state = when {
        snapshot.peers == null -> OverlayHealthState.RUNNING
        snapshot.peers == 0 -> OverlayHealthState.NO_PEERS
        else -> OverlayHealthState.PEERS_REPORTED
    }
    return OverlayHealth(state, snapshot.peers, snapshot.routes)
}

/** Fast initial discovery, then low-frequency retry while idle; no unbounded exponential growth. */
internal fun discoveryRetryDelay(attempt: Int, activeCall: Boolean): Long = when {
    attempt <= 0 -> 2_000L
    attempt == 1 -> 4_000L
    attempt == 2 || activeCall -> 8_000L
    else -> 60_000L
}
