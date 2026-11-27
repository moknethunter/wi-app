package com.mokasas.wikall

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.PrivateKey
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

internal enum class SignalType {
    CHALLENGE, AUTH, READY, PING, PONG, INVITE, ACCEPT, REJECT, BUSY, UNAVAILABLE, TIMEOUT, END, OFFER, ANSWER, ICE, RESTART, REPAIR,
    HANDOFF_REQUEST, HANDOFF_CHECK, HANDOFF_CONFIRM, HANDOFF, ONLINE_NUMBERS, MISSED_CALL, MISSED_ACK, ENROLL_REQUIRED, READY_LOCAL_V5,
    OVERLAY_PROFILE,
    CHAT_READY, CHAT_KEY_REQUEST, CHAT_KEY, CHAT_SEND, CHAT_DELIVERY, CHAT_ACK, CHAT_ACCEPTED, CHAT_DECLINED,
}

private val chatUpstream = setOf(SignalType.CHAT_KEY_REQUEST, SignalType.CHAT_SEND, SignalType.CHAT_ACK)
internal val chatDownstream = setOf(SignalType.CHAT_READY, SignalType.CHAT_KEY, SignalType.CHAT_DELIVERY,
    SignalType.CHAT_ACCEPTED, SignalType.CHAT_DECLINED)
private val numberedSignals = setOf(SignalType.INVITE, SignalType.CHAT_KEY_REQUEST, SignalType.CHAT_KEY,
    SignalType.CHAT_SEND, SignalType.CHAT_DELIVERY, SignalType.CHAT_ACK)

internal data class Signal(val type: SignalType, val id: UUID, val payload: String? = null, val number: String? = null)
internal val probeId: UUID = UUID(0, 0)
internal val onlineNumbersProbe = UUID(0, 1)
internal val missedCallsProbe = UUID(0, 2)
internal val overlayProfileProbe = UUID(0, 3)
private const val MAX_SIGNAL_SIZE = 16 * 1024
private const val SEQUENCED_RESUME = "resume5"
private val RESUME_MARKERS = setOf("resume", SEQUENCED_RESUME)

private object SignalCodec {
    fun encode(signal: Signal): String {
        val payload = signal.payload?.let { Wire.encode(it.encodeToByteArray()) }.orEmpty()
        val text = "4|${signal.type.name}|${signal.id}|${signal.number.orEmpty()}|$payload"
        require(text.length <= MAX_SIGNAL_SIZE)
        return text
    }

    fun decode(text: String): Signal {
        require(text.length in 1..MAX_SIGNAL_SIZE)
        val parts = text.split('|', limit = 6)
        require(parts.size == 5 && parts[0] == "4")
        val type = SignalType.valueOf(parts[1])
        val id = UUID.fromString(parts[2])
        require(id.toString() == parts[2])
        val number = parts[3].ifEmpty { null }
        if (type in numberedSignals) require(number != null && validNumber(number))
        else require(number == null)
        val payload = parts[4].takeIf { it.isNotEmpty() }
            ?.let { Wire.decode(it).decodeToString(throwOnInvalidSequence = true) }
        when (type) {
            SignalType.CHALLENGE -> require(id == probeId && payload != null && validNonce(payload))
            SignalType.AUTH -> require(id == probeId && payload != null && payload.length <= 420)
            SignalType.OFFER, SignalType.ANSWER, SignalType.ICE -> require(id != probeId && payload != null)
            SignalType.ONLINE_NUMBERS -> require(
                id == onlineNumbersProbe && payload != null && payload.length <= 6 * USER_LIMIT + 5 &&
                    payload.split(',').all(::validNumber),
            )
            SignalType.MISSED_CALL -> {
                val fields = requireNotNull(payload).split(',', limit = 3)
                require(fields.size == 2 && validNumber(fields[0]) && (fields[1].toLongOrNull() ?: 0) > 0)
            }
            SignalType.OVERLAY_PROFILE -> require(id == overlayProfileProbe && payload != null && payload.length in 1..8192)
            SignalType.CHAT_READY -> require(id == chatProbe && payload == null)
            SignalType.CHAT_KEY -> require(id.version() == 4 && payload != null && payload.length in 1..600)
            SignalType.CHAT_SEND, SignalType.CHAT_DELIVERY -> require(id.version() == 4 && payload != null && payload.length in 1..4600)
            SignalType.CHAT_KEY_REQUEST, SignalType.CHAT_ACK, SignalType.CHAT_ACCEPTED, SignalType.CHAT_DECLINED ->
                require(id.version() == 4 && payload == null)
            else -> require(payload == null)
        }
        return Signal(type, id, payload, number)
    }

}

internal interface EnrollmentRegistry {
    fun find(number: String): Enrollment?
    fun revoke(number: String)
    fun redeem(number: String, id: String): Boolean = false
    fun enqueueMissed(realm: String, number: String, call: UUID, caller: String) = Unit
    fun pendingMissed(realm: String, number: String): List<Signal> = emptyList()
    fun ackMissed(realm: String, number: String, call: UUID) = Unit
}

internal interface SignalChannel : AutoCloseable {
    fun send(signal: Signal)
}

/** TLS authenticates these fields. The elapsed time avoids relying on synchronized phone clocks. */
internal class SequencedFrames(private val now: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val started = now()
    private var sent = 0L
    private var received = 0L
    private var lastTime = 0L

    fun encode(body: String): String {
        val time = (now() - started).coerceAtLeast(0L)
        return "5|${++sent}|$time|$body"
    }

    fun decode(frame: String): String {
        val fields = frame.split('|', limit = 4)
        require(fields.size == 4 && fields[0] == "5")
        val sequence = requireNotNull(fields[1].toLongOrNull())
        val elapsed = requireNotNull(fields[2].toLongOrNull())
        require(sequence == received + 1 && elapsed >= lastTime)
        received = sequence
        lastTime = elapsed
        return fields[3]
    }
}

private class FramedSocket(private val socket: SSLSocket) : SignalChannel {
    private val input = DataInputStream(socket.inputStream)
    private val output = DataOutputStream(socket.outputStream)
    @Volatile private var sequenced: SequencedFrames? = null

    fun enableSequencing() { sequenced = SequencedFrames() }

    @Synchronized
    override fun send(signal: Signal) {
        val formatted = SignalCodec.encode(signal)
        val bytes = (sequenced?.encode(formatted) ?: formatted).encodeToByteArray()
        require(bytes.size in 1..MAX_SIGNAL_SIZE)
        output.writeShort(bytes.size)
        output.write(bytes)
        output.flush()
    }

    fun receive(): Signal {
        val size = input.readUnsignedShort()
        require(size in 1..MAX_SIGNAL_SIZE)
        val bytes = ByteArray(size)
        input.readFully(bytes)
        val frame = bytes.decodeToString(throwOnInvalidSequence = true)
        return SignalCodec.decode(sequenced?.decode(frame) ?: frame)
    }

    override fun close() = socket.close()
}

internal class LocalHost(
    context: SSLContext,
    address: String,
    listenPort: Int = PORT,
    private val registry: EnrollmentRegistry,
    private val serverPin: String,
    private val acceptsLocalPeer: (String, String) -> Boolean = { _, _ -> true },
    private val onHandshakeFailure: ((Throwable) -> Unit)? = null,
    private val overlayProfile: ((Enrollment) -> ByteArray)? = null,
    private val chat: LocalChatRelay? = null,
) : AutoCloseable {
    private class Callbacks(
        val onReady: (String, SignalChannel) -> Unit,
        val onSignal: (String, Signal) -> Unit,
        val onEnded: (String) -> Unit,
    )

    private val leaseLock = Any()
    private val attempts = Semaphore(3)
    private val closed = AtomicBoolean()
    private val serving = AtomicBoolean()
    private val active = mutableMapOf<String, FramedSocket>()
    private val overlaySubscribers = mutableSetOf<FramedSocket>()
    private val chatSubscribers = mutableSetOf<FramedSocket>()
    private val routes = NumberRouting()
    private val departures = mutableMapOf<String, java.util.concurrent.ScheduledFuture<*>>()
    private val accepted = mutableSetOf<SSLSocket>()
    @Volatile private var callbacks: Callbacks? = null
    private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "wi-call-handshake-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val workers = ThreadPoolExecutor(
        0, USER_LIMIT + 4, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "wi-call-peer").apply { isDaemon = true } },
    )

    private val tlsContext = context
    private val directListeners = mutableMapOf(address to listen(address, listenPort))
    val localPort: Int = directListeners.getValue(address).localPort
    private var overlayListener: SSLServerSocket? = null
    private var overlayAddress: String? = null

    private fun listen(address: String, port: Int) =
        (tlsContext.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            try {
                enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                reuseAddress = true
                bind(InetSocketAddress(address, port), 8)
            } catch (failure: Exception) {
                close()
                throw failure
            }
        }

    fun setDirectAddress(address: String?) = setDirectAddresses(listOfNotNull(address))

    fun setDirectAddresses(addresses: List<String>): Set<String> = synchronized(leaseLock) {
        if (closed.get()) return@synchronized emptySet()
        val wanted = addresses.toSet()
        (directListeners.keys - wanted).forEach { old -> runCatching { directListeners.remove(old)?.close() } }
        (wanted - directListeners.keys).forEach { address ->
            runCatching { listen(address, localPort) }.onSuccess { listener ->
                directListeners[address] = listener
                callbacks?.let { acceptConnections(listener, it) }
            }
        }
        directListeners.keys.toSet()
    }

    fun setOverlayAddress(address: String?) = synchronized(leaseLock) {
        if (closed.get() || address == overlayAddress || address in directListeners) return@synchronized
        runCatching { overlayListener?.close() }
        overlayListener = null
        overlayAddress = null
        if (address == null) return@synchronized
        val handler = callbacks ?: return@synchronized
        overlayListener = listen(address, localPort).also { acceptConnections(it, handler) }
        overlayAddress = address
    }

    fun revokeDevice(number: String) {
        val (peer, ended) = synchronized(leaseLock) {
            registry.revoke(number)
            departures.remove(number)?.cancel(false)
            val connected = active.remove(number)
            connected?.let {
                overlaySubscribers.remove(it)
                chatSubscribers.remove(it)
            }
            if (connected != null) callbacks?.onEnded?.invoke(number)
            connected to routes.disconnected(number)
        }
        ended?.let(::dispatch)
        runCatching { peer?.close() }
    }

    fun refreshOverlayProfiles() {
        val subscribers = synchronized(leaseLock) { active.filterValues { it in overlaySubscribers } }
        subscribers.forEach(::sendOverlayProfile)
    }

    private fun sendOverlayProfile(number: String, socket: FramedSocket) {
        val makeProfile = overlayProfile ?: return
        val payload = synchronized(leaseLock) {
            if (active[number] !== socket || socket !in overlaySubscribers) return
            val enrolled = registry.find(number) ?: return
            if (enrolled.revoked || enrolled.expiresAt?.let { it <= System.currentTimeMillis() } == true) return
            runCatching { Wire.encode(makeProfile(enrolled)) }.getOrNull() ?: return
        }
        runCatching { socket.send(Signal(SignalType.OVERLAY_PROFILE, overlayProfileProbe, payload)) }
            .onFailure { runCatching(socket::close) }
    }

    fun sendAsHost(message: Signal) {
        val routed = synchronized(leaseLock) { routes.receive(HOST_NUMBER, message, ::reachable) }
        if (routed != null) recordUndelivered(HOST_NUMBER, message, routed)
        routed?.let(::dispatch)
    }

    val supportsChat: Boolean get() = chat != null

    fun sendChatAsHost(message: Signal) {
        val replies = synchronized(leaseLock) {
            if (closed.get()) return
            if (message.type == SignalType.PING && message.id == chatProbe) chat?.pending(HOST_NUMBER)
            else chat?.route(HOST_NUMBER, message)
        }
        replies.orEmpty().forEach(::dispatch)
    }

    private fun recordUndelivered(caller: String, sent: Signal, routed: RoutedSignal) {
        val number = sent.number ?: return
        if (sent.type != SignalType.INVITE || routed.message.type != SignalType.UNAVAILABLE) return
        val assigned = registry.find(number) ?: return
        if (!assigned.revoked && (assigned.expiresAt == null || assigned.expiresAt > System.currentTimeMillis())) {
            registry.enqueueMissed(serverPin, number, sent.id, caller)
        }
    }

    private fun reachable(number: String): Boolean = number == HOST_NUMBER || number in active

    private fun dispatch(delivery: RoutedSignal) {
        if (delivery.to == HOST_NUMBER) {
            callbacks?.onSignal?.invoke(delivery.from, delivery.message)
        } else {
            val target = synchronized(leaseLock) {
                active[delivery.to]?.takeIf { delivery.message.type !in chatDownstream || it in chatSubscribers }
            } ?: return
            runCatching { target.send(delivery.message) }.onFailure { runCatching(target::close) }
        }
    }

    fun serve(
        onReady: (String, SignalChannel) -> Unit,
        onSignal: (String, Signal) -> Unit,
        onEnded: (String) -> Unit,
    ) {
        check(serving.compareAndSet(false, true))
        val handler = Callbacks(onReady, onSignal, onEnded)
        synchronized(leaseLock) {
            if (closed.get()) return
            callbacks = handler
            directListeners.values.forEach { acceptConnections(it, handler) }
            overlayListener?.let { acceptConnections(it, handler) }
        }
    }

    private fun acceptConnections(socketListener: SSLServerSocket, handler: Callbacks) {
        thread(name = "wi-call-listener", isDaemon = true) {
            while (!closed.get()) {
                val socket = try {
                    socketListener.accept() as SSLSocket
                } catch (_: IOException) {
                    return@thread
                }
                val allowed = runCatching {
                    acceptsLocalPeer(socket.localAddress.hostAddress.orEmpty(), socket.inetAddress.hostAddress.orEmpty())
                }.getOrDefault(false)
                if (!allowed || !attempts.tryAcquire()) {
                    runCatching(socket::close)
                    continue
                }
                synchronized(leaseLock) {
                    if (closed.get()) {
                        attempts.release()
                        runCatching(socket::close)
                        return@thread
                    }
                    accepted.add(socket)
                }
                try {
                    workers.execute { servePeer(socket, handler) }
                } catch (_: RejectedExecutionException) {
                    synchronized(leaseLock) { accepted.remove(socket) }
                    attempts.release()
                    runCatching(socket::close)
                }
            }
        }
    }

    private fun servePeer(socket: SSLSocket, handler: Callbacks) {
        var handshaken = false
        var hasPermit = true
        var deadline: java.util.concurrent.ScheduledFuture<*>? = null
        var activationExpiry: java.util.concurrent.ScheduledFuture<*>? = null
        var owner: FramedSocket? = null
        var number: String? = null
        try {
            deadline = deadlines.schedule({ runCatching(socket::close) }, 10, TimeUnit.SECONDS)
            socket.soTimeout = 8_000
            socket.startHandshake()
            handshaken = true
            val channel = FramedSocket(socket)
            owner = channel
            val challenge = Wire.token()
            channel.send(Signal(SignalType.CHALLENGE, probeId, challenge))
            var hello = channel.receive()
            val resume = hello.payload?.split('.')?.getOrNull(1)
            val sequenced = resume == SEQUENCED_RESUME
            if (resume in RESUME_MARKERS &&
                synchronized(leaseLock) { requiresEnrollment(hello, challenge) }
            ) {
                channel.send(Signal(SignalType.ENROLL_REQUIRED, probeId))
                hello = channel.receive()
                require(hello.payload?.split('.')?.getOrNull(1) !in RESUME_MARKERS)
            }
            var oldSocket: FramedSocket? = null
            var replacedCall: RoutedSignal? = null
            val user = synchronized(leaseLock) {
                check(!closed.get())
                val assigned = authorize(hello, challenge)
                registry.find(assigned)?.expiresAt?.let { expires ->
                    activationExpiry = deadlines.schedule(
                        { runCatching(socket::close) },
                        (expires - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS,
                    )
                }
                number = assigned
                channel.send(Signal(if (sequenced) SignalType.READY_LOCAL_V5 else SignalType.READY, probeId))
                if (sequenced) channel.enableSequencing()
                oldSocket = active.put(assigned, channel)
                departures.remove(assigned)?.cancel(false)
                if (oldSocket != null && !routes.hasAcceptedCall(assigned))
                    replacedCall = routes.disconnected(assigned)
                handler.onReady(assigned, channel)
                assigned
            }
            oldSocket?.let { runCatching(it::close) }
            replacedCall?.let(::dispatch)
            deadline.cancel(false)
            attempts.release()
            hasPermit = false
            socket.soTimeout = 35_000
            while (!closed.get()) {
                val message = channel.receive()
                require(message.type !in setOf(SignalType.AUTH, SignalType.CHALLENGE, SignalType.READY, SignalType.ENROLL_REQUIRED, SignalType.READY_LOCAL_V5))
                if (message.type in setOf(SignalType.ONLINE_NUMBERS, SignalType.MISSED_CALL, SignalType.OVERLAY_PROFILE)) break
                if (message.type in chatDownstream) break
                if (message.type in chatUpstream) {
                    val replies = synchronized(leaseLock) {
                        if (active[user] === channel && channel in chatSubscribers) chat?.route(user, message) else null
                    }
                    replies.orEmpty().forEach(::dispatch)
                    continue
                }
                if (message.type == SignalType.MISSED_ACK) {
                    synchronized(leaseLock) {
                        if (active[user] === channel) registry.ackMissed(serverPin, user, message.id)
                    }
                    continue
                }
                if (message.type == SignalType.PING) {
                    if (message.id == onlineNumbersProbe) {
                        val numbers = synchronized(leaseLock) {
                            if (active[user] === channel) (active.keys + HOST_NUMBER).sorted().joinToString(",") else null
                        } ?: break
                        channel.send(Signal(SignalType.ONLINE_NUMBERS, onlineNumbersProbe, numbers))
                    } else if (message.id == missedCallsProbe) {
                        val items = synchronized(leaseLock) {
                            if (active[user] === channel) registry.pendingMissed(serverPin, user) else emptyList()
                        }
                        items.forEach(channel::send)
                    } else if (message.id == overlayProfileProbe && overlayProfile != null) {
                        synchronized(leaseLock) {
                            if (active[user] === channel) overlaySubscribers.add(channel)
                        }
                        sendOverlayProfile(user, channel)
                    } else if (message.id == chatProbe && chat != null) {
                        val pending = synchronized(leaseLock) {
                            if (active[user] !== channel) return@synchronized null
                            chatSubscribers.add(channel)
                            chat.pending(user)
                        } ?: break
                        channel.send(Signal(SignalType.CHAT_READY, chatProbe))
                        pending.forEach { channel.send(it.message) }
                    } else channel.send(Signal(SignalType.PONG, message.id))
                } else if (message.type == SignalType.PONG) {
                    synchronized(leaseLock) { if (active[user] === channel) handler.onSignal(user, message) }
                } else {
                    val routed = synchronized(leaseLock) {
                        if (active[user] === channel) routes.receive(user, message, ::reachable)?.also {
                            recordUndelivered(user, message, it)
                        } else null
                    }
                    routed?.let(::dispatch)
                }
            }
        } catch (failure: Exception) {
            if (!handshaken) onHandshakeFailure?.invoke(failure)
        } catch (failure: Error) {
            if (!handshaken) onHandshakeFailure?.invoke(failure)
            throw failure
        } finally {
            deadline?.cancel(false)
            activationExpiry?.cancel(false)
            if (hasPermit) attempts.release()
            runCatching(socket::close)
            val ended = synchronized(leaseLock) {
                accepted.remove(socket)
                owner?.let {
                    overlaySubscribers.remove(it)
                    chatSubscribers.remove(it)
                }
                val user = number
                if (user != null && owner != null && active[user] === owner) {
                    active.remove(user)
                    handler.onEnded(user)
                    if (closed.get()) routes.disconnected(user)
                    else if (!routes.hasAcceptedCall(user)) {
                        routes.disconnected(user) { id, caller ->
                            runCatching { registry.enqueueMissed(serverPin, user, id, caller) }
                        }
                    } else {
                        departures[user] = deadlines.schedule({
                            val delivery = synchronized(leaseLock) {
                                departures.remove(user)
                                if (user !in active) routes.disconnected(user) else null
                            }
                            delivery?.let(::dispatch)
                        }, 16, TimeUnit.SECONDS)
                        null
                    }
                } else null
            }
            ended?.let(::dispatch)
        }
    }

    private data class AuthClaim(val number: String, val id: String, val signature: String, val device: Enrollment)

    private fun claim(hello: Signal): AuthClaim {
        require(hello.type == SignalType.AUTH)
        val parts = checkNotNull(hello.payload).split('.')
        require(parts.size == 3)
        val (number, id, signature) = parts
        require(number in userNumbers)
        val device = requireNotNull(registry.find(number))
        require(!device.revoked && (device.expiresAt == null || device.expiresAt > System.currentTimeMillis()))
        return AuthClaim(number, id, signature, device)
    }

    private fun requiresEnrollment(hello: Signal, challenge: String): Boolean {
        val (number, id, signature, device) = claim(hello)
        require(id in RESUME_MARKERS && device.expiresAt != null)
        require(Wire.verified(publicKey(device.signing), resumeProof(serverPin, number, challenge), Wire.decode(signature)))
        return !device.activated
    }

    private fun authorize(hello: Signal, challenge: String): String {
        val (number, id, signature, device) = claim(hello)
        if (id in RESUME_MARKERS) {
            require(device.expiresAt != null && device.activated)
            require(Wire.verified(publicKey(device.signing), resumeProof(serverPin, number, challenge), Wire.decode(signature)))
        } else {
            require(Wire.equal(device.activationId, id))
            require(Wire.verified(publicKey(device.signing), activationProof(serverPin, number, id, challenge), Wire.decode(signature)))
            if (device.expiresAt != null) require(!device.activated && registry.redeem(number, id))
        }
        return number
    }

    override fun close() {
        val sockets = synchronized(leaseLock) {
            if (!closed.compareAndSet(false, true)) return
            active.clear()
            routes.clear()
            departures.values.forEach { it.cancel(false) }
            departures.clear()
            directListeners.values.forEach { runCatching(it::close) }
            directListeners.clear()
            runCatching { overlayListener?.close() }
            overlayListener = null
            overlayAddress = null
            accepted.toList()
        }
        sockets.forEach { runCatching(it::close) }
        workers.shutdown()
        deadlines.shutdownNow()
    }
}

internal class EndpointUnavailable(message: String, cause: Throwable) : IOException(message, cause)

internal class LocalClient(
    private val host: String,
    pin: String,
    private val port: Int = PORT,
    localNetwork: SocketFactory? = null,
    private val localAddress: java.net.InetAddress? = null,
) : SignalChannel {
    private val context = pinnedTls(pinnedTrust(pin))
    private val raw: Socket = localNetwork?.createSocket() ?: Socket()
    private val closed = AtomicBoolean()
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var channel: FramedSocket? = null

    fun connect(activation: Activation, signing: PrivateKey): LocalClient {
        var handshaken = false
        try {
            check(!closed.get())
            if (localAddress != null) raw.bind(InetSocketAddress(localAddress, 0))
            raw.connect(InetSocketAddress(host, port), 4_000)
            val encrypted = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            socket = encrypted
            check(!closed.get())
            encrypted.soTimeout = 8_000
            encrypted.enabledProtocols = encrypted.supportedProtocols
                .filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            encrypted.startHandshake()
            handshaken = true
            val framed = FramedSocket(encrypted)
            channel = framed
            val challenge = framed.receive()
            require(challenge.type == SignalType.CHALLENGE)
            val nonce = checkNotNull(challenge.payload)
            fun enroll(): Signal {
                val signature = Wire.encode(Wire.signature(signing, activation.proof(nonce)))
                framed.send(Signal(SignalType.AUTH, probeId, "${activation.number}.${activation.id}.$signature"))
                return framed.receive()
            }
            val ready = if (activation.expiresAt == null) enroll() else {
                val signature = Wire.encode(Wire.signature(signing, resumeProof(activation.pin, activation.number, nonce)))
                framed.send(Signal(SignalType.AUTH, probeId, "${activation.number}.$SEQUENCED_RESUME.$signature"))
                val reply = framed.receive()
                if (reply == Signal(SignalType.ENROLL_REQUIRED, probeId)) enroll() else reply
            }
            check(ready.id == probeId && ready.type in setOf(SignalType.READY, SignalType.READY_LOCAL_V5))
            if (ready.type == SignalType.READY_LOCAL_V5) framed.enableSequencing()
            encrypted.soTimeout = 25_000
            return this
        } catch (error: Exception) {
            close()
            val status = when {
                handshaken -> "رفض الخادم تفعيل هذا الجهاز. راجع رقم المستخدم وحالة تفعيله."
                error is SSLException -> "تعذّر التحقق من هوية الخادم. استخدم ملف تفعيل صادرًا من هذا الخادم."
                else -> "تعذّر الوصول إلى $host. تحقق من تشغيل الخادم وإمكانية الوصول بين الهاتفين."
            }
            if (handshaken) throw IOException(status, error)
            throw EndpointUnavailable(status, error)
        }
    }

    override fun send(signal: Signal) = checkNotNull(channel).send(signal)

    fun receive(): Signal = checkNotNull(channel).receive()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket?.close() }
        runCatching(raw::close)
    }
}
