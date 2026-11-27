package com.mokasas.wikall

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

@Suppress("DEPRECATION")
internal class NetworkStateMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start(onChange: () -> Unit) {
        stop()
        val watcher = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange()
            override fun onLost(network: Network) = onChange()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = onChange()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = onChange()
        }
        connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build(), watcher)
        callback = watcher
    }

    fun stop() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
    }

    fun localLinks(): List<Pair<Network, LinkProperties>> = connectivity.allNetworks.mapNotNull { network ->
        val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) return@mapNotNull null
        connectivity.getLinkProperties(network)?.let { network to it }
    }

    fun gateways() = localLinks().flatMap { (_, link) ->
        link.routes.filter { it.isDefaultRoute }.mapNotNull { it.gateway?.hostAddress }.filter(::privateIpv4)
    }

    fun physicalAddresses() = (localLinks().flatMap { (_, link) ->
        link.linkAddresses.mapNotNull { it.address.hostAddress }
    } + hotspotAddresses().mapNotNull { it.address.hostAddress }).filter(::privateIpv4).filterNot(::overlayAddress).distinct()

    fun topology(): List<String> =
        (localLinks().flatMap { (network, link) -> link.linkAddresses.map { "$network:$it" } } + physicalAddresses()).sorted()

    fun routes(destination: String, overlayIp: String?): List<RouteCandidate> {
        if (!privateIpv4(destination)) return emptyList()
        val target = InetAddress.getByName(destination)
        if (overlayAddress(destination)) {
            if (overlayIp == null || !overlayAddress(overlayIp) || destination == overlayIp) return emptyList()
            val source = InetAddress.getByName(overlayIp)
            return connectivity.allNetworks.mapNotNull { network ->
                val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                val link = connectivity.getLinkProperties(network) ?: return@mapNotNull null
                if (link.linkAddresses.none { it.address == source } ||
                    link.routes.none { it.destination.prefixLength > 0 && it.destination.contains(target) }
                ) return@mapNotNull null
                RouteCandidate(destination, LocalPath.OVERLAY_LOCAL, source, network)
            }
        }
        val direct = localLinks().flatMap { (network, link) ->
            link.linkAddresses.mapNotNull {
                if (!allowedSource(it.address, target, it.prefixLength)) null
                else RouteCandidate(destination, LocalPath.LAN_DIRECT, it.address, network)
            }
        }
        val configured = localLinks().flatMap { (_, link) -> link.linkAddresses }.map { it.address }.toSet()
        return direct + hotspotAddresses().mapNotNull {
            if (it.address in configured || !allowedSource(it.address, target, it.networkPrefixLength.toInt())) null
            else RouteCandidate(destination, LocalPath.HOTSPOT_DIRECT, it.address, null)
        }
    }

    fun overlayConflictsWithLocalSubnet(): Boolean {
        val links = localLinks().flatMap { (_, link) -> link.linkAddresses.map { it.address to it.prefixLength } }
        val hotspot = hotspotAddresses().map { it.address to it.networkPrefixLength.toInt() }
        val overlay = InetAddress.getByName(OVERLAY_NETWORK).address
        return (links + hotspot).any { (address, prefix) ->
            address is Inet4Address && prefix in 1..32 &&
                sameIpv4Subnet(overlay, address.address, minOf(prefix, OVERLAY_PREFIX))
        }
    }

    fun acceptsPeer(local: String, remote: String): Boolean {
        if (overlayAddress(local)) return overlayAddress(remote) && local != remote
        if (overlayAddress(remote)) return false
        return routes(remote, null).any { it.source.hostAddress == local }
    }

    private fun allowedSource(source: InetAddress, target: InetAddress, prefix: Int): Boolean =
        source is Inet4Address && source != target && privateIpv4(source.hostAddress.orEmpty()) &&
            !overlayAddress(source.hostAddress.orEmpty()) && prefix in 1..32 &&
            sameIpv4Subnet(source.address, target.address, prefix)

    private fun hotspotAddresses() = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && HOTSPOT_INTERFACES.any(it.name::startsWith) }
        .flatMap { it.interfaceAddresses }.filter { it.address is Inet4Address }

    private companion object {
        val HOTSPOT_INTERFACES = listOf("ap", "softap", "swlan", "wlan", "p2p")
    }
}
