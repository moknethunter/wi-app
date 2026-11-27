package com.mokasas.wikall

import android.net.Network
import java.io.IOException
import java.net.InetAddress
import java.security.PrivateKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class TransportType { LOCAL_LAN, HOTSPOT, OVERLAY_LOCAL, INTERNET_WEBRTC, RELAY }

internal enum class LocalPath(val type: TransportType, val priority: Int, val label: String) {
    LAN_DIRECT(TransportType.LOCAL_LAN, 0, "Wi-Fi / LAN"),
    HOTSPOT_DIRECT(TransportType.HOTSPOT, 1, "نقطة اتصال محلية"),
    OVERLAY_LOCAL(TransportType.OVERLAY_LOCAL, 2, "EasyTier محلي"),
}

internal data class RouteCandidate(val address: String, val path: LocalPath, val source: InetAddress, val network: Network?)

internal enum class TransportState { IDLE, CONNECTING, READY, FAILED }

internal interface Transport {
    val type: TransportType
    suspend fun start(): Result<Unit>
    suspend fun stop(): Result<Unit>
    fun observeState(): StateFlow<TransportState>
}

/** Owns one authenticated signaling socket. The call and its media outlive this scope. */
internal abstract class LocalSignalingTransport(
    override val type: TransportType,
    val client: LocalClient,
    private val activation: Activation,
    private val signing: PrivateKey,
) : Transport {
    private val state = MutableStateFlow(TransportState.IDLE)
    private val observable = state.asStateFlow()

    override suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        state.value = TransportState.CONNECTING
        runCatching {
            client.connect(activation, signing)
            Unit
        }.also {
            state.value = if (it.isSuccess) TransportState.READY else TransportState.FAILED
        }
    }

    override suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { client.close() }.also { state.value = TransportState.IDLE }
    }

    override fun observeState() = observable
}

internal class LocalLanTransport(route: RouteCandidate, activation: Activation, signing: PrivateKey) :
    LocalSignalingTransport(
        TransportType.LOCAL_LAN,
        LocalClient(route.address, activation.pin, localNetwork = requireNotNull(route.network).socketFactory, localAddress = route.source),
        activation, signing,
    )

internal class HotspotTransport(route: RouteCandidate, activation: Activation, signing: PrivateKey) :
    LocalSignalingTransport(
        TransportType.HOTSPOT, LocalClient(route.address, activation.pin, localAddress = route.source), activation, signing,
    )

internal class OverlayTransport(route: RouteCandidate, activation: Activation, signing: PrivateKey) :
    LocalSignalingTransport(
        TransportType.OVERLAY_LOCAL,
        LocalClient(route.address, activation.pin, localNetwork = requireNotNull(route.network).socketFactory, localAddress = route.source),
        activation, signing,
    )

@Retention(AnnotationRetention.SOURCE)
internal annotation class FutureInternetTransport

@FutureInternetTransport
internal interface InternetWebRtcTransport : Transport {
    override val type get() = TransportType.INTERNET_WEBRTC
    override suspend fun start(): Result<Unit> = throw UnsupportedOperationException("Internet transport not available in offline mode")
    override suspend fun stop(): Result<Unit> = throw UnsupportedOperationException("Internet transport not available in offline mode")
}

@FutureInternetTransport
internal interface RelayTransport : Transport {
    override val type get() = TransportType.RELAY
    override suspend fun start(): Result<Unit> = throw UnsupportedOperationException("Internet transport not available in offline mode")
    override suspend fun stop(): Result<Unit> = throw UnsupportedOperationException("Internet transport not available in offline mode")
}

internal interface MultiPathTransport {
    fun candidates(addresses: List<String>, overlayIp: String?): List<RouteCandidate>
}

internal enum class PathState { CONNECTING, READY, FAILED }

internal data class PathEvent(
    val path: LocalPath,
    val state: PathState,
    val at: Long,
    val latency: Long? = null,
    val reason: String? = null,
    val lossFraction: Double? = null,
    val jitterMs: Double? = null,
    val cost: Int = path.priority,
)

internal fun prioritizeRoutes(routes: List<RouteCandidate>): List<RouteCandidate> =
    routes.distinct().sortedBy { it.path.priority }

internal class PathManager(private val monitor: NetworkStateMonitor) : MultiPathTransport {
    private val events = MutableStateFlow<List<PathEvent>>(emptyList())
    val state = events.asStateFlow()

    override fun candidates(addresses: List<String>, overlayIp: String?) =
        prioritizeRoutes(addresses.distinct().flatMap { monitor.routes(it, overlayIp) })

    fun reachable(address: String, overlayIp: String?) = monitor.routes(address, overlayIp).isNotEmpty()

    fun available(route: RouteCandidate, overlayIp: String?): Boolean = route in monitor.routes(route.address, overlayIp)

    fun prepare(route: RouteCandidate, activation: Activation, signing: PrivateKey, overlayIp: String?): LocalSignalingTransport {
        if (!available(route, overlayIp)) throw IOException("The local interface is no longer available")
        return when (route.path) {
            LocalPath.LAN_DIRECT -> LocalLanTransport(route, activation, signing)
            LocalPath.HOTSPOT_DIRECT -> HotspotTransport(route, activation, signing)
            LocalPath.OVERLAY_LOCAL -> OverlayTransport(route, activation, signing)
        }
    }

    fun record(path: LocalPath, state: PathState, latency: Long? = null, reason: String? = null) {
        events.value = (events.value + PathEvent(path, state, System.currentTimeMillis(), latency, reason)).takeLast(32)
    }

    fun measure(sample: MediaPathSample, overlayIp: String?): LocalPath? {
        val local = monitor.routes(sample.remoteIp, overlayIp).firstOrNull { it.source.hostAddress == sample.localIp } ?: return null
        events.value = (events.value + PathEvent(
            local.path, PathState.READY, System.currentTimeMillis(), sample.roundTripMs,
            lossFraction = sample.lossFraction, jitterMs = sample.jitterMs,
        )).takeLast(32)
        return local.path
    }

    fun clear() { events.value = emptyList() }
}
