package com.mokasas.wikall

import android.net.VpnService
import android.os.ParcelFileDescriptor

/** Binds each new EasyTier socket while native code still owns its descriptor. */
internal class AndroidOverlaySocketPolicy(
    private val vpn: VpnService,
    sources: List<String>,
) : OverlaySocketPolicy {
    private val allowed = sources.toSet()
    private val monitor = NetworkStateMonitor(vpn)

    override fun prepare(fd: Int, source: String): Int = runCatching {
        if (fd < 0 || source !in allowed) return -1
        val networks = monitor.localLinks().filter { (_, link) ->
            link.linkAddresses.any { it.address.hostAddress == source }
        }
        if (networks.size > 1) return -1
        val network = networks.singleOrNull()?.first
        val interfaceIndex = if (network == null) monitor.hotspotInterfaceIndex(source) ?: return -1 else 0
        if (!vpn.protect(fd)) return -1

        // Binding a duplicate updates the same kernel socket and preserves Rust ownership.
        if (network != null) ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) }
        interfaceIndex
    }.getOrDefault(-1)
}
