package com.mokasas.wikall

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper

@Suppress("DEPRECATION")
internal class LanDiscovery(
    context: Context,
    private val type: String = "_mokasas._tcp.",
    private val servicePort: Int = PORT,
    private val label: String = "Wi-Call",
) {
    private val manager = requireNotNull(context.getSystemService(NsdManager::class.java))
    private val main = Handler(Looper.getMainLooper())
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var discoveryTimeout: Runnable? = null

    fun advertise(pin: String, network: Network? = null, attributes: Map<String, String> = emptyMap()) {
        close()
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
            override fun onServiceRegistered(info: NsdServiceInfo) {
                main.post {
                    if (registration !== this) runCatching { manager.unregisterService(this) }
                }
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        }
        registration = listener
        val info = NsdServiceInfo().apply {
            serviceName = "$label ${discoveryId(pin).take(12)}"
            serviceType = type
            port = servicePort
            setAttribute("id", discoveryId(pin))
            attributes.forEach { (key, value) -> setAttribute(key, value) }
            if (Build.VERSION.SDK_INT >= 33 && network != null) this.network = network
        }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun find(pin: String, result: (String?) -> Unit) {
        findPeers(pin, emptySet(), limit = 1) { result(it.firstOrNull()) }
    }

    fun findPeers(
        pin: String,
        ignoredAddresses: Set<String>,
        limit: Int,
        network: Network? = null,
        accept: (String, NsdServiceInfo) -> Boolean = { _, _ -> true },
        result: (List<String>) -> Unit,
    ) {
        close()
        val id = discoveryId(pin)
        val listener = object : NsdManager.DiscoveryListener {
            val addresses = linkedSetOf<String>()
            val pending = ArrayDeque<NsdServiceInfo>()
            val seen = mutableSetOf<Pair<String, Network?>>()
            var resolving = false

            fun finish() {
                if (discovery !== this) return
                close()
                result(addresses.toList())
            }

            fun resolveNext() {
                if (discovery !== this || resolving || pending.isEmpty()) return
                resolving = true
                val info = pending.removeFirst()
                val scan = this
                val resolve = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, code: Int) {
                        main.post { resolved(null) }
                    }

                    override fun onServiceResolved(service: NsdServiceInfo) {
                        main.post { resolved(service) }
                    }

                    fun resolved(service: NsdServiceInfo?) {
                        if (discovery !== scan) return
                        resolving = false
                        if (service != null && service.port == servicePort &&
                            service.attributes["id"]?.decodeToString() == id) {
                            service.host?.hostAddress?.takeIf {
                                privateIpv4(it) && !overlayAddress(it) && it !in ignoredAddresses && accept(it, service)
                            }?.let(addresses::add)
                        }
                        if (addresses.size >= limit) finish() else resolveNext()
                    }
                }
                runCatching { manager.resolveService(info, resolve) }.onFailure { resolve.resolved(null) }
            }

            override fun onStartDiscoveryFailed(type: String, code: Int) {
                main.post { finish() }
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onDiscoveryStarted(type: String) {
                main.post { if (discovery !== this) runCatching { manager.stopServiceDiscovery(this) } }
            }
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    val network = if (Build.VERSION.SDK_INT >= 33) info.network else null
                    if (discovery === this && seen.size < USER_LIMIT + 1 &&
                        info.serviceName.startsWith("$label ${id.take(12)}") && seen.add(info.serviceName to network)) {
                        pending.addLast(info)
                        resolveNext()
                    }
                }
            }
        }
        discovery = listener
        discoveryTimeout = Runnable { listener.finish() }.also { main.postDelayed(it, 3_000) }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33 && network != null) {
                manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, network, { main.post(it) }, listener)
            } else manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        }
            .onFailure { listener.finish() }
    }

    fun close() {
        discoveryTimeout?.let(main::removeCallbacks)
        discoveryTimeout = null
        discovery?.let { runCatching { manager.stopServiceDiscovery(it) } }
        registration?.let { runCatching { manager.unregisterService(it) } }
        discovery = null
        registration = null
    }

}
