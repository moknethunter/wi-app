package com.mokasas.wikall

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetAddress
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val AUTOMATIC_RELAY_PORT = 11011

internal fun relayDiscovery(context: Context) =
    LanDiscovery(context, "_wikall-relay._udp.", AUTOMATIC_RELAY_PORT, "WiKall relay")

internal fun relayProof(secret: String, pin: String, relay: String, client: String): String =
    Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(secret.encodeToByteArray(), "HmacSHA256"))
    }.doFinal("WiKall relay 1\n$pin\n$relay\n$client".encodeToByteArray())
        .joinToString("") { "%02x".format(it) }

internal class AutomaticWifiRelay(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val hostDiscovery = LanDiscovery(context)
    private val peerDiscovery = LanDiscovery(context, "_wikall-ol._udp.", EASYTIER_RELAY_PORT, "WiKall")
    private val announcement = relayDiscovery(context)
    private var bridge: EasyTierDatagramRelay? = null
    private var attempt = 0

    fun refresh(request: OverlayRequest) {
        close()
        if (Build.VERSION.SDK_INT < 33 || request.number == HOST_NUMBER) return
        val links = wifiRelayLinks(context).distinctBy { it.network }
        if (links.size != 2) return
        val (first, second) = links
        if (first.contains(second.ip) || second.contains(first.ip)) return
        val current = attempt
        tryDirection(request, second, first, current) {
            tryDirection(request, first, second, current) { retry(request, current) }
        }
    }

    private fun tryDirection(
        request: OverlayRequest,
        hostLink: WifiRelayLink,
        clientLink: WifiRelayLink,
        current: Int,
        unavailable: () -> Unit,
    ) {
        hostDiscovery.findPeers(request.pin, emptySet(), 1, hostLink.network,
            accept = { address, _ -> hostLink.contains(InetAddress.getByName(address)) },
        ) { hosts ->
            if (current != attempt) return@findPeers
            val host = hosts.firstOrNull()
            if (host == null) {
                unavailable()
                return@findPeers
            }
            val own = setOfNotNull(clientLink.ip.hostAddress)
            peerDiscovery.findPeers(request.pin, own, 1, clientLink.network,
                accept = { address, _ -> clientLink.contains(InetAddress.getByName(address)) },
            ) scan@{ clients ->
                if (current != attempt) return@scan
                val client = clients.firstOrNull()
                if (client == null || !start(request, clientLink, hostLink, client, host, current)) unavailable()
            }
        }
    }

    private fun start(
        request: OverlayRequest,
        clientLink: WifiRelayLink,
        hostLink: WifiRelayLink,
        client: String,
        host: String,
        current: Int,
    ): Boolean = runCatching {
        val relay = checkNotNull(clientLink.ip.hostAddress)
        val clientIp = InetAddress.getByName(client) as Inet4Address
        val hostIp = InetAddress.getByName(host) as Inet4Address
        val clientSocket = clientLink.socket(clientIp, AUTOMATIC_RELAY_PORT)
        val hostSocket = try {
            hostLink.socket(hostIp, 0)
        } catch (failure: Exception) {
            clientSocket.close()
            throw failure
        }
        val tunnel = EasyTierDatagramRelay(
            EasyTierDatagramRelay.Leg(clientSocket, clientIp, EASYTIER_RELAY_PORT, initiator = true),
            EasyTierDatagramRelay.Leg(hostSocket, hostIp, EASYTIER_RELAY_PORT),
        ) { main.post { retry(request, current) } }
        bridge = tunnel
        tunnel.start()
        announcement.advertise(request.pin, clientLink.network,
            mapOf("target" to client, "proof" to relayProof(request.secret, request.pin, relay, client)))
    }.isSuccess

    private val retryToken = Any()
    private fun retry(request: OverlayRequest, current: Int) {
        if (current != attempt) return
        announcement.close()
        bridge?.close()
        bridge = null
        main.postAtTime({ if (current == attempt) refresh(request) }, retryToken, android.os.SystemClock.uptimeMillis() + 30_000)
    }

    fun close() {
        attempt++
        main.removeCallbacksAndMessages(retryToken)
        hostDiscovery.close()
        peerDiscovery.close()
        announcement.close()
        bridge?.close()
        bridge = null
    }
}
