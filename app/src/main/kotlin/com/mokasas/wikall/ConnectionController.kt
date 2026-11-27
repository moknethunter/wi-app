package com.mokasas.wikall

import android.content.Context
import android.net.Network
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class Mode { HOME, SERVER, CLIENT }

internal data class SessionState(
    val mode: Mode = Mode.HOME,
    val status: String = "",
    val address: String? = null,
    val path: LocalPath? = null,
    val mediaPath: LocalPath? = null,
    val number: String? = null,
    val devices: List<Enrollment> = emptyList(),
    val onlineUsers: Set<String> = emptySet(),
    val peer: String? = null,
    val latency: Long? = null,
    val busy: Boolean = false,
    val callId: UUID? = null,
    val callState: CallState = CallState.IDLE,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val overlayStatus: String = "",
    val overlayHealth: OverlayHealth? = null,
    val ice: String = "",
    val missed: List<IncomingMissed> = emptyList(),
)

@Singleton
internal class ConnectionController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: CallStore,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newCachedThreadPool()
    private val hostControl = Executors.newSingleThreadExecutor()
    private val sender = Executors.newSingleThreadExecutor()
    val chat = ChatSessionManager(store, main, sender)
    // Accessed only on hostControl, including while the listener is still starting.
    private var boundHost: LocalHost? = null
    private val discovery = LanDiscovery(context)
    private val overlayDiscovery = LanDiscovery(context, "_wikall-ol._udp.", OVERLAY_PORT, "WiKall")
    private val masterAnnouncements = LocalAnnouncements { LanDiscovery(context) }
    private val overlayAnnouncement = LocalAnnouncements { LanDiscovery(context, "_wikall-ol._udp.", OVERLAY_PORT, "WiKall") }
    @Volatile private var generation = 0
    private var host: LocalHost? = null
    private var client: LocalClient? = null
    private var clientRoute: RouteCandidate? = null
    private var channel: SignalChannel? = null
    private var lanAttempt = 0
    private var savedClient: SavedConnection? = null
    private val overlayPreferences = context.getSharedPreferences("wi-call-overlay", Context.MODE_PRIVATE)
    private val overlayCredentials = OverlayCredentials(overlayPreferences)
    internal var overlayRequest: OverlayRequest? = null
        private set
    @Volatile private var overlayIp: String? = null
    private var overlayPeer: String? = null
    private var discoveredOverlayPeers = emptyList<String>()
    private val directory = DirectorySessionManager(store)
    private var monitoring = false
    private var topology: List<String>? = null
    private val recovery = RecoveryManager(main)
    private var hotspotPoll: Runnable? = null
    private val networkMonitor = NetworkStateMonitor(context)
    private val pathManager = PathManager(networkMonitor)
    private val onlineChannels = mutableMapOf<String, SignalChannel>()
    private var identity: ServerIdentity? = null
    private var hostAddress: String? = null
    private var waiting: Pair<UUID, Long>? = null
    private var timer: Runnable? = null
    private var heartbeat: Runnable? = null
    private var lastHeartbeatAt = 0L
    private var probeTimeout: Runnable? = null
    private var retry: Runnable? = null
    private var retryCount = 0
    private var call = CallSessionManager()
    private var voice: VoiceMedia? = null
    private val pendingMedia = ArrayDeque<Signal>()
    private var trackedId: UUID? = null
    private var trackedState = CallState.IDLE
    private val mutable = MutableStateFlow(SessionState())
    val state = mutable.asStateFlow()
    var onUpdate: ((SessionState) -> Unit)? = null

    fun listAddresses(): List<String> = (networkMonitor.physicalAddresses() + listOfNotNull(overlayIp)).distinct()

    private fun physicalAddresses(): List<String> = networkMonitor.physicalAddresses()

    fun start(address: String?) {
        val local = address?.takeIf { it in physicalAddresses() }
        stop()
        monitorNetwork(serveHotspot = true)
        publish(SessionState(mode = Mode.SERVER, busy = true, status = "جارٍ تشغيل الخادم…"))
        bind(local ?: physicalAddresses().firstOrNull())
    }

    private fun bind(address: String?) {
        val attempt = ++lanAttempt
        val current = generation
        hostControl.execute {
            val result = runCatching {
                check(current == generation)
                runCatching { boundHost?.close() }
                boundHost = null
                val keys = ServerIdentity.load()
                overlayCredentials.recoverInterruptedRevocation()
                val server = address?.let {
                    runCatching {
                        val pin = Wire.pin(keys.entry.certificate.publicKey)
                        val relay = runCatching { LocalChatRelay(pin, store, store, DeviceIdentity.load().request) }.getOrNull()
                        LocalHost(keys.tls(), it, registry = store, serverPin = pin,
                            acceptsLocalPeer = networkMonitor::acceptsPeer,
                            overlayProfile = { device ->
                                issueCredential(keys, device, null).export(keys.entry.privateKey, publicKey(device.encryption))
                            }, chat = relay)
                    }.getOrNull()
                }
                boundHost = server
                keys to server
            }
            main.post {
                if (current != generation || attempt != lanAttempt) return@post
                result.onSuccess { (keys, server) ->
                    host = server
                    identity = keys
                    val attached = server?.setDirectAddresses(physicalAddresses()).orEmpty()
                    hostAddress = address?.takeIf { it in attached } ?: attached.firstOrNull()
                    val pin = Wire.pin(keys.entry.certificate.publicKey)
                    advertiseMaster(pin, attached)
                    publish(state.value.copy(address = hostAddress, number = HOST_NUMBER, busy = false,
                        status = if (hostAddress != null) "الخادم المحلي جاهز." else "الخادم المحلي غير متاح على هذه الشبكة."))
                    refreshUsers()
                    directory.attach(pin, HOST_NUMBER)
                    loadMissed()
                    server?.serve(
                        { number, connection -> onMain(current) {
                            if (attempt != lanAttempt) return@onMain
                            onlineChannels[number] = connection
                            if (state.value.peer == number && call.accepted) {
                                selectPeer(number, connection)
                                if (call.state != CallState.CONNECTED) requestRepair()
                            }
                            publish(state.value.copy(onlineUsers = onlineChannels.keys.toSet(), status = "المستخدم $number متصل."))
                        } },
                        { number, message -> onMain(current) { if (attempt == lanAttempt) receivedFrom(number, message) } },
                        { number -> onMain(current) {
                            if (attempt != lanAttempt) return@onMain
                            onlineChannels.remove(number)
                            if (state.value.peer == number)
                                disconnect("انقطع المسار المحلي للمستخدم $number.", preserveAccepted = true)
                            publish(state.value.copy(onlineUsers = onlineChannels.keys.toSet()))
                        } },
                    )
                    if (server != null) {
                        chat.connect(pin, HOST_NUMBER, server.supportsChat) { signal ->
                            sender.execute { if (current == generation) runCatching { server.sendChatAsHost(signal) } }
                        }
                        sender.execute { if (current == generation) runCatching { server.sendChatAsHost(Signal(SignalType.PING, chatProbe)) } }
                        syncHostOverlay(pin)
                        runCatching { server.setOverlayAddress(overlayIp) }
                    }
                }.onFailure {
                    publish(state.value.copy(busy = false, status = "تعذّر تشغيل الخادم. تحقق من عنوان الشبكة والمنفذ."))
                }
            }
        }
    }

    suspend fun issue(request: DeviceRequest, preferredNumber: String? = null): Pair<String, ByteArray> {
        val keys = checkNotNull(identity)
        val address = checkNotNull(hostAddress)
        val issued = withContext(Dispatchers.IO) {
            val device = store.issue(request, preferredNumber)
            val activation = issueCredential(keys, device, address)
            device.number to activation.export(keys.entry.privateKey, request.encryption)
        }
        refreshUsers()
        return issued
    }

    private fun issueCredential(keys: ServerIdentity, device: Enrollment, address: String?): Activation {
        val (secret, epoch) = overlayCredentials.current()
        return Activation(address, keys.entry.certificate.publicKey, device.number, device.serial,
            device.activationId, secret, device.expiresAt, epoch)
    }

    fun revoke(number: String) {
        if (identity == null) return
        val server = host
        val current = generation
        sender.execute {
            val revoked = runCatching {
                overlayCredentials.revokeDevice { if (server != null) server.revokeDevice(number) else store.revoke(number) }
            }.getOrNull()
            if (revoked == true) server?.refreshOverlayProfiles()
            onMain(current) {
                if (revoked == true) refreshOverlay()
                val message = when (revoked) {
                    true -> "أُلغي تفعيل $number وحُدّث مفتاح EasyTier."
                    false -> "أُلغي تفعيل $number. تعذّر حفظ مفتاح EasyTier الجديد."
                    null -> "تعذّر إلغاء تفعيل المستخدم."
                }
                publish(state.value.copy(status = message))
                refreshUsers()
            }
        }
    }

    private fun refreshUsers() {
        val current = generation
        sender.execute {
            val users = runCatching(store::devices).getOrNull() ?: return@execute
            onMain(current) {
                publish(state.value.copy(devices = users))
            }
        }
    }

    fun connect(activation: Activation, file: ByteArray) {
        stop()
        monitorNetwork()
        locate(SavedConnection(activation.address, activation, file))
    }

    fun reconnect() {
        stop()
        monitorNetwork()
        publish(SessionState(mode = Mode.CLIENT, busy = true, status = "جارٍ قراءة الاتصال المحفوظ…"))
        val current = generation
        worker.execute {
            val saved = runCatching { store.saved(DeviceIdentity.load()) }.getOrNull()
            onMain(current) {
                if (saved == null) publish(SessionState(mode = Mode.CLIENT, status = "استورد ملف تفعيل صادرًا لهذا الهاتف."))
                else locate(saved)
            }
        }
    }

    fun forget() {
        stop()
        sender.execute { runCatching(store::forget) }
        publish(SessionState(mode = Mode.CLIENT, status = "حُذف الاتصال المحفوظ من هذا الهاتف."))
    }

    private fun locate(saved: SavedConnection) {
        retry?.let(main::removeCallbacks)
        retry = null
        savedClient = saved
        val attempt = ++lanAttempt
        overlayDiscovery.close()
        publish(state.value.copy(mode = Mode.CLIENT, busy = true, address = saved.address, number = saved.activation.number, status = "جارٍ البحث عن الخادم المحلي…"))
        directory.attach(saved.pin, saved.activation.number)
        loadMissed()
        val current = generation
        discovery.find(saved.pin) { address ->
            if (current == generation && attempt == lanAttempt) {
                syncClientOverlay(saved, address)
                if (address == null) discoverOverlayRoutes(saved, current, attempt)
                val gateways = runCatching(networkMonitor::gateways).getOrDefault(emptyList())
                val candidates = lanCandidates(address, gateways, saved.address, saved.activation.address)
                    .filterNot(::overlayAddress) + overlayFallback(null)
                val targets = candidates.distinct()
                if (targets.isNotEmpty()) open(saved.copy(address = targets.first()), targets.drop(1))
                else {
                    disconnect("بانتظار اكتشاف الخادم المحلي.", preserveAccepted = true)
                    scheduleDiscoveryRetry()
                }
            }
        }
    }

    private fun discoverOverlayRoutes(saved: SavedConnection, current: Int, attempt: Int) {
        if (saved.activation.overlaySecret == null || android.net.VpnService.prepare(context) != null) return
        val own = runCatching(::listAddresses).getOrDefault(emptyList()).toSet()
        overlayDiscovery.findPeers(saved.pin, own, limit = 6) { peers ->
            if (current == generation && attempt == lanAttempt && channel == null) {
                discoveredOverlayPeers = peers.filter { networkMonitor.routes(it, null).isNotEmpty() }
                syncClientOverlay(saved, null)
            }
        }
    }

    private fun overlayFallback(existing: String?): List<String> =
        if (overlayIp != null && overlayRequest?.number != HOST_NUMBER && existing != OVERLAY_HOST) {
            listOf(OVERLAY_HOST)
        } else emptyList()

    private fun announcementLinks(addresses: Set<String>): Set<Network?> {
        if (addresses.isEmpty()) return emptySet()
        if (android.os.Build.VERSION.SDK_INT < 33) return setOf(null)
        val result = mutableSetOf<Network?>()
        val scoped = mutableSetOf<String>()
        networkMonitor.localLinks().forEach { (network, properties) ->
            val matches = properties.linkAddresses.mapNotNull { it.address.hostAddress }.filter { it in addresses }
            if (matches.isNotEmpty()) {
                result.add(network)
                scoped.addAll(matches)
            }
        }
        // Android may omit its downstream hotspot link from ConnectivityManager.
        if (scoped != addresses) result.add(null)
        return result
    }

    private fun advertiseMaster(pin: String, addresses: Set<String>) =
        masterAnnouncements.update(pin, announcementLinks(addresses))

    private fun refreshOverlayAnnouncements(request: OverlayRequest) =
        overlayAnnouncement.update(request.pin, announcementLinks(physicalAddresses().toSet()))

    suspend fun hasSavedActivation(): Boolean = withContext(Dispatchers.IO) {
        runCatching { store.saved(DeviceIdentity.load()) != null }.getOrDefault(false)
    }

    fun refreshOverlay() {
        val server = identity
        val client = savedClient
        if (server != null && host != null) syncHostOverlay(Wire.pin(server.entry.certificate.publicKey))
        else if (client != null) syncClientOverlay(client, overlayPeer)
    }

    private fun syncHostOverlay(pin: String) {
        if (android.net.VpnService.prepare(context) != null) return
        val addresses = physicalAddresses()
        if (addresses.isEmpty()) return
        openOverlay(OverlayRequest(pin, overlayCredentials.current().first, HOST_NUMBER, null, listenerAddresses = addresses))
    }

    private fun syncClientOverlay(saved: SavedConnection, detected: String?) {
        val secret = saved.activation.overlaySecret ?: return
        if (android.net.VpnService.prepare(context) != null) return
        if (detected != null && !overlayAddress(detected)) overlayPeer = detected
        val address = overlayPeer ?: saved.activation.address
        val listeners = physicalAddresses()
        if (listeners.isEmpty()) return
        val peer = address?.takeIf { networkMonitor.routes(it, null).isNotEmpty() }
        val peers = discoveredOverlayPeers.filter { it != peer && networkMonitor.routes(it, null).isNotEmpty() }.sorted()
        openOverlay(OverlayRequest(saved.pin, secret, saved.activation.number, peer, peers, listenerAddresses = listeners))
    }

    private fun openOverlay(request: OverlayRequest) {
        if (overlayRequest == request) return
        overlayRequest = request
        publish(state.value.copy(overlayStatus = "جارٍ تشغيل EasyTier…", overlayHealth = null))
        runCatching { context.startForegroundService(Intent(context, WikallVpnService::class.java)) }
            .onFailure { overlayChanged(request, null) }
    }

    /** Pause the overlay route while native DHCP or TUN reconstruction is pending. */
    internal fun overlayAwaitingAddress(request: OverlayRequest) {
        if (overlayRequest != request) return
        overlayIp = null
        runCatching { host?.setOverlayAddress(null) }
        publish(state.value.copy(overlayStatus = "بانتظار تخصيص عنوان النفق المحلي…", overlayHealth = null))
    }

    internal fun overlayChanged(request: OverlayRequest, address: String?) {
        if (overlayRequest != request) return
        val firstReady = overlayIp == null && address != null
        overlayIp = address
        if (address == null) {
            overlayRequest = null
            overlayAnnouncement.close()
        } else {
            refreshOverlayAnnouncements(request)
        }
        val attached = runCatching { host?.setOverlayAddress(address) }.isSuccess
        val status = when {
            address == null -> "تعذّر تشغيل شبكة EasyTier أو تم إيقافها."
            !attached -> "التراكب جاهز، لكن تعذّر فتح منفذ المكالمات عليه."
            else -> "تم تشغيل النفق المحلي؛ الاتصال بالأقران قيد التحقق."
        }
        publish(state.value.copy(overlayStatus = status, overlayHealth = null))
        if (request.number != HOST_NUMBER) {
            val saved = savedClient ?: return
            val activeOverlay = clientRoute?.takeIf { it.path == LocalPath.OVERLAY_LOCAL }
            val lostTunnel = address == null && activeOverlay != null
            val changedSource = address != null && activeOverlay != null &&
                activeOverlay.source.hostAddress != address
            if (lostTunnel || changedSource) recoverClient(saved, "تغيّر نفق EasyTier. جارٍ استعادة الاتصال المحلي.")
            else if (firstReady && channel == null) {
                retry?.let(main::removeCallbacks)
                retry = null
                locate(saved)
            }
        }
    }

    /** Only accept diagnostics belonging to the current running tunnel. */
    internal fun overlayHealthChanged(request: OverlayRequest, health: OverlayHealth) {
        if (overlayRequest != request || overlayIp == null) return
        if (state.value.overlayHealth != health) publish(state.value.copy(overlayHealth = health))
    }

    /** Retry only discovery/transport failures; authentication rejection is not retried. */
    private fun scheduleDiscoveryRetry() {
        retry?.let(main::removeCallbacks)
        val current = generation
        val attempt = lanAttempt
        val delay = discoveryRetryDelay(retryCount, call.accepted)
        retryCount = (retryCount + 1).coerceAtMost(3)
        retry = Runnable {
            retry = null
            if (current == generation && attempt == lanAttempt && monitoring &&
                state.value.mode == Mode.CLIENT && channel == null) {
                savedClient?.let(::locate)
            }
        }.also { main.postDelayed(it, delay) }
    }

    private fun open(saved: SavedConnection, fallback: List<String> = emptyList()) {
        val routes = pathManager.candidates(listOfNotNull(saved.address) + fallback, overlayIp)
        val route = routes.firstOrNull()
        if (route == null) {
            disconnect("لا يوجد مسار محلي إلى الخادم. بانتظار Wi-Fi أو نقطة اتصال.", preserveAccepted = true)
            scheduleDiscoveryRetry()
            return
        }
        openRoute(saved.copy(address = route.address), route, routes.drop(1))
    }

    private fun openRoute(saved: SavedConnection, route: RouteCandidate, fallback: List<RouteCandidate>) {
        val attempt = lanAttempt
        val current = generation
        pathManager.record(route.path, PathState.CONNECTING)
        publish(state.value.copy(busy = true, address = route.address, path = route.path, status = "جارٍ التحقق من هوية الخادم…"))
        worker.execute {
            var online = false
            var transport: LocalSignalingTransport? = null
            try {
                val device = DeviceIdentity.load()
                require(device.request.serial == saved.activation.serial)
                val trusted = store.saved(device)
                require(trusted == null || Wire.equal(trusted.pin, saved.pin)) {
                    "هذا الهاتف موثّق بخادم آخر. انس التفعيل المحفوظ قبل الانتقال إلى خادم جديد."
                }
                val latest = saved.retainingNewerOverlay(trusted)
                val connection = try {
                    pathManager.prepare(route, latest.activation, device.signing, overlayIp)
                } catch (failure: java.io.IOException) {
                    throw EndpointUnavailable("تعذّر فتح اتصال على الشبكة الحالية.", failure)
                }
                transport = connection
                val active = connection.client
                main.post {
                    if (current == generation && attempt == lanAttempt) client = active else active.close()
                }
                runBlocking { connection.start().getOrThrow() }
                online = true
                onMain(current) {
                    if (attempt != lanAttempt) return@onMain
                    pathManager.record(route.path, PathState.READY)
                    val connected = latest.retainingNewerOverlay(savedClient)
                    savedClient = connected
                    retryCount = 0
                    channel = active
                    clientRoute = route
                    publish(state.value.copy(
                        peer = if (call.id == null) HOST_NUMBER else state.value.peer, busy = false,
                        status = "متصل بالخادم المحلي.",
                    ))
                    sender.execute saveProfile@{
                        if (current != generation) return@saveProfile
                        val persisted = runCatching {
                            connected.retainingNewerOverlay(store.saved(device)).also(store::save)
                        }.getOrNull()
                        onMain(current) savedOnMain@{
                            if (channel !== active) return@savedOnMain
                            if (persisted == null) {
                                publish(state.value.copy(status = "متصل. تعذّر حفظ الاقتران على الهاتف."))
                            } else updateClientOverlay(persisted)
                        }
                    }
                    sendTo(active, Signal(SignalType.PING, onlineNumbersProbe))
                    sendTo(active, Signal(SignalType.PING, missedCallsProbe))
                    sendTo(active, Signal(SignalType.PING, overlayProfileProbe))
                    chat.connect(connected.pin, connected.activation.number, false) { signal -> sendTo(active, signal) }
                    sendTo(active, Signal(SignalType.PING, chatProbe))
                    if (connected !== saved) syncClientOverlay(connected, route.address)
                    probe()
                    if (call.accepted && call.state != CallState.CONNECTED) requestRepair()
                    lastHeartbeatAt = SystemClock.elapsedRealtime()
                    scheduleHeartbeat(active)
                }
                while (true) {
                    val message = active.receive()
                    onMain(current) {
                        if (attempt == lanAttempt)
                            received(message)
                    }
                }
            } catch (failure: Exception) {
                onMain(current) {
                    if (attempt != lanAttempt) return@onMain
                    val latest = savedClient ?: saved
                    val addressFailure = failure is EndpointUnavailable
                    pathManager.record(route.path, PathState.FAILED, reason = if (addressFailure) "unreachable" else "connection_lost")
                    if (!online && addressFailure && fallback.isNotEmpty()) {
                        openRoute(latest.copy(address = fallback.first().address), fallback.first(), fallback.drop(1))
                        return@onMain
                    }
                    if (online && call.accepted) {
                        val addresses = fallback.map { it.address } + overlayFallback(route.address)
                        val alternates = pathManager.candidates(addresses, overlayIp).filterNot { it == route }
                        if (alternates.isNotEmpty()) {
                            disconnect("انقطع المسار الأساسي. جارٍ تجربة مسار محلي آخر.", preserveAccepted = true)
                            lanAttempt++
                            val next = alternates.first()
                            openRoute(latest.copy(address = next.address), next, alternates.drop(1))
                            return@onMain
                        }
                    }
                    val reason = when {
                        online -> "انقطع اتصال الخادم."
                        addressFailure -> "تعذّر الوصول إلى الخادم المحلي. سيعيد التطبيق البحث تلقائيًا."
                        else -> failure.message.orEmpty().ifBlank { "تعذّر الاتصال المحلي." }
                    }
                    disconnect(reason, preserveAccepted = true)
                    if (online || addressFailure) scheduleDiscoveryRetry()
                }
            } finally {
                transport?.let { used -> runBlocking { used.stop() } }
            }
        }
    }

    fun dial(peer: String) {
        val snapshot = state.value
        if (call.id != null || !validNumber(peer) || peer == snapshot.number) return
        when (snapshot.mode) {
            Mode.SERVER -> {
                if (host == null) return
                selectPeer(peer, onlineChannels[peer])
            }
            Mode.CLIENT -> {
                if (channel == null) return
                selectPeer(peer, channel)
            }
            Mode.HOME -> return
        }
        val invite = call.dial()?.copy(number = peer) ?: return
        send(invite)
        changed()
    }

    fun decide(type: SignalType) {
        call.decide(type)?.let(::send)
        changed()
    }

    fun startMedia(id: UUID) {
        if (call.id != id || !call.accepted || voice != null) return
        val media = VoiceMedia(
            context,
            { type, payload -> if (call.id == id && call.accepted) send(Signal(type, id, payload)) },
            { status ->
                if (call.id == id) {
                    val recovering = status == CallState.RECONNECTING && call.state != CallState.RECONNECTING
                    call.media(status)
                    changed()
                    if (recovering) requestRepair()
                }
            },
            { if (call.id == id) failMedia() },
            localAddresses = ::listAddresses,
            allowsRemoteAddress = { pathManager.reachable(it, overlayIp) },
            onIceState = { if (call.id == id) publish(state.value.copy(ice = it)) },
            onStats = { sample ->
                if (call.id == id) {
                    val selected = pathManager.measure(sample, overlayIp)
                    if (selected != state.value.mediaPath) publish(state.value.copy(mediaPath = selected))
                }
            },
        )
        voice = media
        call.media(CallState.CONNECTING)
        changed()
        try {
            media.start(call.outgoing)
            while (pendingMedia.isNotEmpty()) {
                val signal = pendingMedia.removeFirst()
                if (signal.payload != null) media.receive(signal.type, signal.payload)
            }
        } catch (_: Exception) {
            failMedia()
        } catch (_: LinkageError) {
            failMedia()
        }
    }

    fun failMedia() {
        if (!call.accepted) return
        call.failed()?.let(::send)
        changed()
    }

    fun audioServiceEnded(id: UUID) {
        if (call.id == id && voice != null) failMedia()
    }

    fun toggleMute() {
        val active = voice ?: return
        val mute = !state.value.muted
        active.mute(mute)
        publish(state.value.copy(muted = mute))
    }

    fun toggleSpeaker() {
        val enabled = !state.value.speaker
        if (voice?.speaker(enabled) == true) publish(state.value.copy(speaker = enabled))
    }

    private fun scheduleHeartbeat(active: SignalChannel) {
        heartbeat?.let(main::removeCallbacks)
        val current = generation
        val interval = if (call.id == null) IDLE_HEARTBEAT_MS else ACTIVE_HEARTBEAT_MS
        val remaining = (interval - (SystemClock.elapsedRealtime() - lastHeartbeatAt)).coerceAtLeast(0)
        heartbeat = Runnable {
            heartbeat = null
            if (current == generation && channel === active) {
                lastHeartbeatAt = SystemClock.elapsedRealtime()
                val id = if (state.value.mode == Mode.CLIENT &&
                    (call.id == null || state.value.onlineUsers.isEmpty())) onlineNumbersProbe else UUID.randomUUID()
                sendTo(active, Signal(SignalType.PING, id))
                scheduleHeartbeat(active)
            }
        }.also { main.postDelayed(it, remaining) }
    }

    fun probe() {
        if (channel == null) return
        clearProbe()
        val id = UUID.randomUUID()
        waiting = id to SystemClock.elapsedRealtime()
        send(Signal(SignalType.PING, id))
        probeTimeout = Runnable {
            clearProbe()
            publish(state.value.copy(latency = null, status = "انتهت مهلة فحص الاتصال."))
        }.also { main.postDelayed(it, 5_000L) }
    }

    private fun clearProbe() {
        waiting = null
        probeTimeout?.let(main::removeCallbacks)
        probeTimeout = null
    }

    private fun selectPeer(number: String, target: SignalChannel?) {
        if (channel !== target || state.value.peer != number) {
            clearProbe()
            channel = target
            publish(state.value.copy(peer = number, latency = null))
        }
    }

    private fun receivedFrom(number: String, message: Signal) {
        if (message.type in chatDownstream) {
            chat.receive(message)
            return
        }
        if (call.id == null && message.type == SignalType.INVITE) {
            val source = onlineChannels[number] ?: return
            selectPeer(number, source)
        }
        if (state.value.peer == number) received(message)
    }

    private fun received(message: Signal) {
        if (message.type in chatDownstream) {
            chat.receive(message)
            return
        }
        if (state.value.mode == Mode.CLIENT && call.id == null && message.type == SignalType.INVITE) {
            val caller = message.number ?: return
            selectPeer(caller, channel)
        }
        when (message.type) {
            SignalType.OVERLAY_PROFILE -> receiveOverlayProfile(message)
            SignalType.MISSED_CALL -> {
                val active = channel ?: return
                val account = directory.account ?: return
                if (state.value.mode != Mode.CLIENT) return
                val fields = message.payload.orEmpty().split(',', limit = 3)
                if (fields.size != 2 || !validNumber(fields[0])) return
                val ended = fields[1].toLongOrNull() ?: return
                val item = IncomingMissed(account, message.id.toString(), fields[0], ended)
                val current = generation
                sender.execute {
                    val inserted = runCatching { store.recordMissed(item) }.getOrNull() ?: return@execute
                    onMain(current) {
                        if (channel === active) sendTo(active, Signal(SignalType.MISSED_ACK, message.id))
                        loadMissed()
                        if (inserted != -1L) notifyMissed(item)
                    }
                }
            }
            SignalType.ONLINE_NUMBERS -> {
                val session = state.value
                if (session.mode == Mode.CLIENT && channel != null) {
                    val numbers = message.payload.orEmpty().split(',').filter { validNumber(it) && it != session.number }
                    publish(session.copy(onlineUsers = numbers.toSet()))
                }
            }
            SignalType.RESTART, SignalType.REPAIR -> {
                if (message.id == call.id && call.accepted) {
                    if (message.type == SignalType.REPAIR && call.outgoing) requestRepair()
                    if (message.type == SignalType.RESTART && !call.outgoing) voice?.restart(false)
                }
            }
            SignalType.PING -> channel?.let { sendTo(it, Signal(SignalType.PONG, message.id)) }
            SignalType.PONG -> waiting?.takeIf { it.first == message.id }?.let {
                clearProbe()
                val latency = SystemClock.elapsedRealtime() - it.second
                state.value.path?.let { path -> pathManager.record(path, PathState.READY, latency) }
                publish(state.value.copy(latency = latency))
            }
            SignalType.OFFER, SignalType.ANSWER, SignalType.ICE -> {
                if (message.id != call.id || !call.accepted) return
                if (message.type == SignalType.OFFER && call.outgoing || message.type == SignalType.ANSWER && !call.outgoing) return
                val media = voice
                if (media != null) media.receive(message.type, checkNotNull(message.payload))
                else if (pendingMedia.size < 40) pendingMedia.add(message)
            }
            else -> {
                call.remote(message)?.let(::send)
                changed()
            }
        }
    }

    private fun receiveOverlayProfile(message: Signal) {
        if (state.value.mode != Mode.CLIENT || channel == null) return
        val current = generation
        val attempt = lanAttempt
        val prior = savedClient ?: return
        sender.execute {
            if (current != generation) return@execute
            val updated = runCatching {
                val device = DeviceIdentity.load()
                val bytes = Wire.decode(checkNotNull(message.payload))
                val credential = Activation.read(bytes, device.decryption, device.request.serial)
                val stored = store.saved(device) ?: prior
                if (!credential.canRefreshOverlay(stored.activation)) return@execute
                SavedConnection(prior.address, credential, bytes).also(store::save)
            }.getOrNull() ?: return@execute
            onMain(current) {
                if (attempt == lanAttempt) updateClientOverlay(updated)
            }
        }
    }

    private fun updateClientOverlay(updated: SavedConnection) {
        if (savedClient?.activation?.let(updated.activation::canRefreshOverlay) != true) return
        savedClient = updated
        syncClientOverlay(updated, overlayPeer)
    }

    private fun changed() {
        val id = call.id
        val next = call.state
        if (id == null) {
            voice?.close()
            voice = null
            pendingMedia.clear()
            val completed = trackedId
            val peer = state.value.peer
            if (completed != null && peer != null) {
                val item = CallHistory(completed.toString(), peer, next.name, System.currentTimeMillis())
                val incomingMissed = !call.outgoing && next == CallState.MISSED
                val account = directory.account
                val current = generation
                sender.execute {
                    runCatching {
                        store.record(item)
                        if (incomingMissed && account != null) {
                            store.recordMissed(IncomingMissed(account, item.id, item.peer, item.endedAt))
                            onMain(current) { loadMissed() }
                        }
                    }
                }
            }
        }
        if ((trackedId == null) != (id == null)) {
            if (heartbeat != null) channel?.let(::scheduleHeartbeat)
            if (hotspotPoll != null) scheduleHotspotCheck()
        }
        if (id != trackedId || next != trackedState) {
            timer?.let(main::removeCallbacks)
            timer = null
            if (id != null) {
                val delay = when (next) {
                    CallState.CALLING -> 32_000L
                    CallState.RINGING -> 30_000L
                    CallState.ACCEPTED, CallState.CONNECTING -> 30_000L
                    CallState.RECONNECTING -> 20_000L
                    else -> null
                }
                if (delay != null) timer = Runnable {
                    if (call.id != id || call.state != next) return@Runnable
                    when (next) {
                        CallState.RINGING -> decide(SignalType.TIMEOUT)
                        CallState.CALLING -> {
                            send(Signal(SignalType.END, id))
                            call.remote(Signal(SignalType.TIMEOUT, id))
                            changed()
                        }
                        else -> failMedia()
                    }
                }.also { main.postDelayed(it, delay) }
            }
            trackedId = id
            trackedState = next
        }
        publish(state.value.copy(callId = id, callState = next, muted = id != null && state.value.muted,
            speaker = id != null && state.value.speaker, ice = if (id != null) state.value.ice else "",
            mediaPath = state.value.mediaPath.takeIf { id != null && next == CallState.CONNECTED }))
    }

    private fun send(message: Signal) {
        val server = host
        if (server != null && message.type != SignalType.PING && message.type != SignalType.PONG) {
            sender.execute { server.sendAsHost(message) }
        } else channel?.let { sendTo(it, message) }
    }

    private fun sendTo(active: SignalChannel, message: Signal) {
        sender.execute {
            try {
                active.send(message)
            } catch (_: Exception) {
                runCatching { active.close() }
            }
        }
    }

    private fun disconnect(reason: String, preserveAccepted: Boolean = false) {
        channel = null
        clientRoute = null
        if (state.value.mode == Mode.CLIENT) {
            chat.pause()
            publish(state.value.copy(onlineUsers = emptySet()))
        }
        clearProbe()
        heartbeat?.let(main::removeCallbacks)
        heartbeat = null
        if (preserveAccepted && call.accepted) {
            if (call.state != CallState.CONNECTED) call.media(CallState.RECONNECTING)
        } else call.disconnected()
        changed()
        publish(state.value.copy(peer = state.value.peer.takeIf { preserveAccepted && call.accepted }, path = null, latency = null, busy = false, status = reason))
    }

    fun stop() {
        generation++
        lanAttempt++
        val hadOverlay = overlayRequest != null
        overlayRequest = null
        overlayIp = null
        overlayPeer = null
        discoveredOverlayPeers = emptyList()
        overlayDiscovery.close()
        masterAnnouncements.close()
        overlayAnnouncement.close()
        if (hadOverlay) context.stopService(Intent(context, WikallVpnService::class.java))
        unmonitorNetwork()
        directory.detach()
        chat.detach()
        pathManager.clear()
        savedClient = null
        retry?.let(main::removeCallbacks)
        retry = null
        retryCount = 0
        discovery.close()
        val oldClient = client
        client = null
        host = null
        identity = null
        hostAddress = null
        onlineChannels.clear()
        disconnect("")
        call = CallSessionManager()
        publish(SessionState())
        runCatching { oldClient?.close() }
        hostControl.execute {
            runCatching { boundHost?.close() }
            boundHost = null
        }
    }

    fun markMissedRead(id: String) {
        val account = directory.account ?: return
        val current = generation
        sender.execute {
            if (runCatching { store.markRead(account, id) }.isSuccess) onMain(current) {
                context.getSystemService(NotificationManager::class.java).cancel(id, 40)
                loadMissed()
            }
        }
    }

    private fun notifyMissed(item: IncomingMissed) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (!notifications.areNotificationsEnabled()) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val name = "wikall-local-missed"
        notifications.createNotificationChannel(NotificationChannel(name, "مكالمات WiKall الفائتة", NotificationManager.IMPORTANCE_DEFAULT))
        val action = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(item.id, 40, Notification.Builder(context, name)
            .setSmallIcon(R.drawable.ic_wikall).setContentTitle("مكالمة فائتة من ${item.peer}")
            .setContentIntent(action).setAutoCancel(true).build())
    }

    private fun loadMissed() {
        val account = directory.account ?: return
        val current = generation
        sender.execute {
            val items = runCatching { directory.missed(account) }.getOrDefault(emptyList())
            onMain(current) { if (directory.account == account) publish(state.value.copy(missed = items)) }
        }
    }

    private fun monitorNetwork(serveHotspot: Boolean = false) {
        topology = currentTopology()
        monitoring = true
        runCatching { networkMonitor.start(::scheduleNetworkCheck) }
        if (serveHotspot) scheduleHotspotCheck()
    }

    // Some hotspot interfaces are not reported through ConnectivityManager.
    private fun scheduleHotspotCheck() {
        hotspotPoll?.let(main::removeCallbacks)
        hotspotPoll = Runnable {
            if (monitoring && state.value.mode == Mode.SERVER) {
                if (currentTopology() != topology) scheduleNetworkCheck()
                scheduleHotspotCheck()
            } else hotspotPoll = null
        }.also { main.postDelayed(it, if (call.id == null) IDLE_HOTSPOT_CHECK_MS else ACTIVE_HOTSPOT_CHECK_MS) }
    }

    private fun unmonitorNetwork() {
        if (!monitoring) return
        monitoring = false
        networkMonitor.stop()
        recovery.cancel()
        hotspotPoll?.let(main::removeCallbacks)
        hotspotPoll = null
    }

    private fun currentTopology() = runCatching(networkMonitor::topology).getOrDefault(emptyList())

    private fun scheduleNetworkCheck() {
        main.post {
            if (!monitoring) return@post
            recovery.schedule {
                val previous = topology
                val updated = currentTopology()
                topology = updated
                if (updated == previous) return@schedule
                if (physicalAddresses().isEmpty() && overlayRequest != null) {
                    overlayRequest = null
                    overlayIp = null
                    overlayAnnouncement.close()
                    runCatching { host?.setOverlayAddress(null) }
                    context.stopService(Intent(context, WikallVpnService::class.java))
                    publish(state.value.copy(overlayStatus = "بانتظار شبكة محلية لتشغيل EasyTier.", overlayHealth = null))
                } else overlayRequest?.takeIf { overlayIp != null }?.let(::refreshOverlayAnnouncements)
                retry?.let(main::removeCallbacks)
                retry = null
                when (state.value.mode) {
                    Mode.SERVER -> {
                        val addresses = runCatching(::physicalAddresses).getOrDefault(emptyList())
                        val activeHost = host
                        if (activeHost == null && addresses.isNotEmpty()) bind(addresses.first())
                        else if (activeHost != null) {
                            val attached = activeHost.setDirectAddresses(addresses)
                            val selected = hostAddress?.takeIf { it in attached } ?: attached.firstOrNull()
                            hostAddress = selected
                            directory.account?.substringBefore(':')?.let { advertiseMaster(it, attached) }
                            publish(state.value.copy(address = selected))
                            refreshOverlay()
                        }
                    }
                    Mode.CLIENT -> savedClient?.let { saved ->
                        overlayPeer = null
                        discoveredOverlayPeers = emptyList()
                        val currentRoute = clientRoute
                        if (channel != null && currentRoute != null && pathManager.available(currentRoute, overlayIp)) {
                            syncClientOverlay(saved, currentRoute.address)
                        } else {
                            recoverClient(saved, "تغيّرت الشبكة المحلية.")
                        }
                    }
                    Mode.HOME -> Unit
                }
            }
        }
    }

    private fun recoverClient(saved: SavedConnection, reason: String) {
        retry?.let(main::removeCallbacks)
        retry = null
        discovery.close()
        val old = client
        client = null
        disconnect(reason, preserveAccepted = true)
        locate(saved)
        worker.execute { runCatching { old?.close() } }
    }

    private fun requestRepair() {
        val id = call.id ?: return
        if (!call.accepted || voice == null) return
        if (channel == null && host == null) return
        if (call.outgoing) {
            if (voice?.canRestart() != true) return
            send(Signal(SignalType.RESTART, id))
            voice?.restart(true)
        } else {
            send(Signal(SignalType.REPAIR, id))
        }
    }

    private fun publish(snapshot: SessionState) {
        mutable.value = snapshot
        onUpdate?.invoke(snapshot)
    }

    private fun onMain(current: Int, action: () -> Unit) {
        main.post { if (current == generation) action() }
    }

    private companion object {
        // The client read timeout is 25 seconds, including for existing peers.
        const val IDLE_HEARTBEAT_MS = 16_000L
        const val ACTIVE_HEARTBEAT_MS = 10_000L
        const val IDLE_HOTSPOT_CHECK_MS = 30_000L
        const val ACTIVE_HOTSPOT_CHECK_MS = 4_000L
    }
}
