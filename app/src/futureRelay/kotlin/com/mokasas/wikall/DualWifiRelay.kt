package com.mokasas.wikall

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

internal const val EASYTIER_RELAY_PORT = 11010

internal data class WifiRelayLink(val network: Network, val address: LinkAddress) {
    val ip: Inet4Address get() = address.address as Inet4Address

    fun contains(peer: InetAddress): Boolean = peer is Inet4Address &&
        sameIpv4Subnet(ip.address, peer.address, address.prefixLength)

    fun socket(peer: Inet4Address, port: Int = EASYTIER_RELAY_PORT): DatagramSocket {
        require(peer != ip && contains(peer))
        val socket = DatagramSocket(null)
        try {
            network.bindSocket(socket)
            socket.bind(InetSocketAddress(ip, port))
            return socket
        } catch (failure: Exception) {
            socket.close()
            throw failure
        }
    }
}

internal fun wifiRelayLinks(context: Context): List<WifiRelayLink> {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
    return connectivity.allNetworks.flatMap { network ->
        val capabilities = connectivity.getNetworkCapabilities(network)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@flatMap emptyList()
        connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
            .filter { it.address is Inet4Address && privateIpv4(it.address.hostAddress.orEmpty()) }
            .map { WifiRelayLink(network, it) }
    }
}

internal class EasyTierDatagramRelay(
    private val first: Leg,
    private val second: Leg,
    private val onFailure: () -> Unit,
) : Closeable {
    class Leg(val socket: DatagramSocket, val peer: InetAddress, port: Int, val initiator: Boolean = false) {
        @Volatile var destination = InetSocketAddress(peer, port)
            private set

        fun accepts(packet: DatagramPacket): Boolean {
            if (packet.address != peer || (!initiator && packet.port != destination.port)) return false
            if (initiator) destination = InetSocketAddress(peer, packet.port)
            return true
        }
    }

    private val closed = AtomicBoolean()

    fun start() {
        forward(first, second)
        forward(second, first)
    }

    private fun forward(input: Leg, output: Leg) {
        Thread({
            val packet = DatagramPacket(ByteArray(65_507), 65_507)
            try {
                while (!closed.get()) {
                    packet.length = packet.data.size
                    input.socket.receive(packet)
                    if (input.accepts(packet)) {
                        output.socket.send(DatagramPacket(packet.data, packet.length, output.destination))
                    }
                }
            } catch (_: IOException) {
                if (shutdown()) onFailure()
            }
        }, "wikall-easytier-relay").apply {
            isDaemon = true
            start()
        }
    }

    private fun shutdown(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        first.socket.close()
        second.socket.close()
        return true
    }

    override fun close() {
        shutdown()
    }
}
