package com.mokasas.wikall

import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.BeforeClass
import org.junit.AfterClass

class ContractTest {
    private val password = "fixture-only".toCharArray()
    private val store = KeyStore.getInstance("PKCS12").apply {
        fixture.inputStream().use { load(it, password) }
    }
    private val entry = store.getEntry("fixture", KeyStore.PasswordProtection(password)) as KeyStore.PrivateKeyEntry

    @Test fun movingNetworksTriesDiscoveredAndGatewayAddressesBeforeStaleHints() {
        assertEquals(listOf("192.168.43.8", "192.168.43.1", "192.168.1.5", "192.168.1.8"),
            lanCandidates("192.168.43.8", listOf("192.168.43.1", "8.8.8.8", "192.168.43.8"),
                "192.168.1.5", "192.168.1.8"))
        assertEquals(listOf("192.168.43.1", "192.168.1.5"),
            lanCandidates(null, listOf("192.168.43.1"), "192.168.1.5", "192.168.1.5"))
    }

    @Test fun encryptedActivationIsRestrictedToItsDevice() {
        val request = DeviceRequest(first.public, second.public)
        val submitted = DeviceRequest.read(request.export(first.private))
        assertEquals(request.serial, submitted.serial)
        assertThrows(IllegalArgumentException::class.java) {
            DeviceRequest.read(request.export(second.private))
        }
        val host = entry.certificate.publicKey
        val device = allocate(emptyList(), submitted)
        val issued = Activation("192.168.10.1", host, device.number, device.serial, device.activationId)
        val bytes = issued.export(entry.privateKey, submitted.encryption)
        assertFalse(bytes.decodeToString().contains(Wire.pin(host)))
        assertFalse(bytes.decodeToString().contains(device.serial))
        assertEquals(issued, Activation.read(bytes, second.private, device.serial))
        assertThrows(java.security.GeneralSecurityException::class.java) {
            Activation.read(bytes, first.private, device.serial)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Activation.read(bytes, second.private, Wire.pin(second.public))
        }
        val wrongHost = KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair().private
        assertThrows(IllegalArgumentException::class.java) {
            Activation.read(issued.export(wrongHost, submitted.encryption), second.private, device.serial)
        }
        val challenge = Wire.token()
        val response = Wire.signature(first.private, issued.proof(challenge))
        assertTrue(Wire.verified(first.public, issued.proof(challenge), response))
        assertFalse(Wire.verified(first.public, issued.proof(Wire.token()), response))
    }

    @Test fun sharedArchivesAcceptOnlyTheRequestedKindAndBoundedContents() {
        val bytes = DeviceRequest(first.public, second.public).export(first.private)
        val archive = packTransfer(Transfer.REQUEST, bytes)
        assertArrayEquals(bytes, unpackTransfer(Transfer.REQUEST, archive))
        assertThrows(IllegalArgumentException::class.java) { unpackTransfer(Transfer.ACTIVATION, archive) }
        assertThrows(IllegalArgumentException::class.java) { packTransfer(Transfer.REQUEST, ByteArray(FILE_LIMIT + 1)) }
    }

    @Test fun groupPassphraseDoesNotCreateTheSameSecretForSeparateGroups() {
        val passphrase = "fixture-only passphrase"
        val firstGroup = newOverlaySecret(passphrase)
        val secondGroup = newOverlaySecret(passphrase)
        assertTrue(validNonce(firstGroup))
        assertTrue(validNonce(secondGroup))
        assertNotEquals(firstGroup, secondGroup)
        assertThrows(IllegalArgumentException::class.java) { newOverlaySecret("short") }
    }

    @Test fun overlayAddressesAreUniqueAndScopedToTheOverlay() {
        val network = (listOf(HOST_NUMBER) + userNumbers).map {
            OverlayRequest("A".repeat(64), "X".repeat(32), it, null)
        }
        assertEquals(OVERLAY_HOST, network.first().address)
        assertEquals(network.size, network.map { it.address }.toSet().size)
        assertTrue(network.all { overlayAddress(it.address) })
        val peers = OverlayRequest("A".repeat(64), "X".repeat(32), HOST_NUMBER,
            "192.168.1.2", listOf("192.168.1.3", "192.168.1.2"))
        assertEquals(
            listOf("uri = \"udp://192.168.1.2:11010\"", "uri = \"udp://192.168.1.3:11010\""),
            peers.configuration().lineSequence().filter { it.startsWith("uri =") }.toList(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            OverlayRequest("A".repeat(64), "X".repeat(32), HOST_NUMBER, OVERLAY_HOST)
        }
    }

    @Test fun localOverlayHasNoRendezvousOrDefaultRoute() {
        val profile = OverlayRequest("A".repeat(64), "X".repeat(32), "99001", "192.168.1.3",
            listenerAddresses = listOf("192.168.1.2")).configuration()
        assertTrue("stun_servers = []" in profile)
        assertTrue("enable_exit_node = false" in profile)
        assertTrue("disable_udp_hole_punching = true" in profile)
        assertTrue("disable_tcp_hole_punching = true" in profile)
        assertTrue("udp://192.168.1.2:$OVERLAY_PORT" in profile)
        assertFalse("0.0.0.0" in profile)
    }

    @Test fun sourceRouteChecksNonOctetSubnetPrefixes() {
        val local = java.net.InetAddress.getByName("192.168.5.132").address
        assertTrue(sameIpv4Subnet(local, java.net.InetAddress.getByName("192.168.5.190").address, 26))
        assertFalse(sameIpv4Subnet(local, java.net.InetAddress.getByName("192.168.5.192").address, 26))
    }

    @Test fun assignedNumberAndQrRemainBoundToTheRequestedDevice() {
        val device = DeviceRequest(first.public, second.public)
        val issued = allocate(emptyList(), device, "99937")
        assertEquals("99937", issued.number)
        assertThrows(IllegalArgumentException::class.java) {
            allocate(listOf(issued), DeviceRequest(second.public, first.public), issued.number)
        }
        val activation = Activation("192.168.10.1", entry.certificate.publicKey, issued.number, issued.serial, issued.activationId, Wire.token())
        val bytes = activation.export(entry.privateKey, device.encryption)
        val qr = activationQrText(bytes)
        assertArrayEquals(bytes, activationQrBytes(qr))
        com.google.zxing.qrcode.QRCodeWriter().encode(qr, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0)
        assertEquals(activation, Activation.read(activationQrBytes(qr), second.private, device.serial))
    }

    @Test fun numbersAreStableAndLimitedToThirtyDistinctDevices() {
        val devices = mutableListOf<Enrollment>()
        for (index in 1..USER_LIMIT) {
            val request = DeviceRequest(keys().public, second.public)
            val issued = allocate(devices, request)
            assertEquals((99000 + index).toString(), issued.number)
            devices.add(issued)
            assertEquals(issued, allocate(devices, request))
        }
        assertThrows(CapacityReached::class.java) { allocate(devices, DeviceRequest(first.public, second.public)) }

        val request = DeviceRequest(first.public, second.public)
        val issued = allocate(emptyList(), request)
        val renewed = allocate(listOf(issued.copy(revoked = true)), request)
        assertEquals(issued.number, renewed.number)
        assertNotEquals(issued.activationId, renewed.activationId)
        assertFalse(renewed.revoked)
    }

    @Test fun consentAndTerminalSignalsAreIdempotent() {
        val caller = LocalCall()
        val receiver = LocalCall()
        val invitation = requireNotNull(caller.dial())
        receiver.remote(invitation)
        assertEquals(CallState.RINGING, receiver.state)
        assertEquals(CallState.CALLING, caller.state)
        val other = UUID.randomUUID()
        assertEquals(Signal(SignalType.BUSY, other), receiver.remote(Signal(SignalType.INVITE, other)))
        val accept = requireNotNull(receiver.decide(SignalType.ACCEPT))
        assertNull(receiver.decide(SignalType.ACCEPT))
        caller.remote(accept)
        caller.remote(accept)
        assertEquals(CallState.ACCEPTED, caller.state)
        val end = requireNotNull(receiver.decide(SignalType.END))
        assertNull(receiver.decide(SignalType.END))
        caller.remote(end)
        caller.remote(end)
        receiver.remote(invitation)
        assertEquals(CallState.ENDED, receiver.state)
        assertEquals(CallState.ENDED, caller.state)
    }

    @Test fun rejectsAndTimesOut() {
        for (decision in listOf(SignalType.REJECT, SignalType.TIMEOUT)) {
            val caller = LocalCall()
            val receiver = LocalCall()
            receiver.remote(requireNotNull(caller.dial()))
            val signal = requireNotNull(receiver.decide(decision))
            assertNull(receiver.decide(decision))
            caller.remote(signal)
            assertEquals(if (decision == SignalType.REJECT) CallState.REJECTED else CallState.MISSED, caller.state)
        }
    }

    @Test fun realSocketSupportsTwoIdentitiesAndRejectsCopiesAndRevokedNumbers() {
        val context = ServerIdentity(entry).tls()
        val pub = entry.certificate.publicKey
        val a = allocate(emptyList(), DeviceRequest(first.public, first.public))
        val b = allocate(listOf(a), DeviceRequest(second.public, second.public))
        val users = mutableMapOf(a.number to a, b.number to b)
        val registry = object : EnrollmentRegistry {
            override fun find(number: String) = users[number]
            override fun revoke(number: String) { users[number]?.let { users[number] = it.copy(revoked = true) } }
            override fun redeem(number: String, id: String): Boolean {
                val user = users[number] ?: return false
                if (user.activationId != id || user.activated || user.revoked) return false
                users[number] = user.copy(activated = true)
                return true
            }
        }
        fun activation(user: Enrollment) = Activation(null, pub, user.number, user.serial, user.activationId,
            expiresAt = user.expiresAt)
        val seen = LinkedBlockingQueue<Pair<String, Signal>>()
        val epoch = java.util.concurrent.atomic.AtomicLong()
        val initialSecret = Wire.token()
        val rotatedSecret = Wire.token()
        LocalHost(context, "127.0.0.1", 0, registry, Wire.pin(pub), overlayProfile = { device ->
            val version = epoch.get()
            activation(device).copy(overlaySecret = if (version == 0L) initialSecret else rotatedSecret, overlayEpoch = version)
                .export(entry.privateKey, publicKey(device.encryption))
        }).use { host ->
            host.serve({ _, _ -> }, { number, signal -> seen.add(number to signal) }, {})
            LocalClient("127.0.0.1", "F".repeat(64), host.localPort).use { client ->
                assertThrows(IOException::class.java) { client.connect(activation(a), first.private) }
            }
            LocalClient("127.0.0.1", Wire.pin(pub), host.localPort).use { client ->
                assertThrows(IOException::class.java) { client.connect(activation(a), second.private) }
            }
            LocalClient("127.0.0.1", Wire.pin(pub), host.localPort).use { firstClient ->
                LocalClient("127.0.0.1", Wire.pin(pub), host.localPort).use { secondClient ->
                    firstClient.connect(activation(a), first.private)
                    secondClient.connect(activation(b), second.private)
                    LocalClient("127.0.0.1", Wire.pin(pub), host.localPort).use { replay ->
                        assertThrows(IOException::class.java) {
                            replay.connect(activation(a).copy(expiresAt = null), first.private)
                        }
                    }

                    firstClient.send(Signal(SignalType.PING, onlineNumbersProbe))
                    assertEquals(
                        Signal(SignalType.ONLINE_NUMBERS, onlineNumbersProbe, "$HOST_NUMBER,${a.number},${b.number}"),
                        firstClient.receive(),
                    )
                    val call = Signal(SignalType.INVITE, UUID.randomUUID(), number = HOST_NUMBER)
                    firstClient.send(call)
                    assertEquals(a.number to call.copy(number = a.number), seen.poll(5, TimeUnit.SECONDS))
                    host.sendAsHost(Signal(SignalType.REJECT, call.id))
                    assertEquals(Signal(SignalType.REJECT, call.id), firstClient.receive())

                    val peerCall = Signal(SignalType.INVITE, UUID.randomUUID(), number = b.number)
                    firstClient.send(peerCall)
                    assertEquals(peerCall.copy(number = a.number), secondClient.receive())
                    secondClient.send(Signal(SignalType.ACCEPT, peerCall.id))
                    assertEquals(Signal(SignalType.ACCEPT, peerCall.id), firstClient.receive())
                    val offer = Signal(SignalType.OFFER, peerCall.id, "sample audio offer")
                    firstClient.send(offer)
                    assertEquals(offer, secondClient.receive())
                    firstClient.close()
                    host.setDirectAddress("127.0.0.2")
                    LocalClient("127.0.0.2", Wire.pin(pub), host.localPort).use { reconnected ->
                        reconnected.connect(activation(a), first.private)
                        val repair = Signal(SignalType.REPAIR, peerCall.id)
                        secondClient.send(repair)
                        assertEquals(repair, reconnected.receive())
                        val restart = Signal(SignalType.RESTART, peerCall.id)
                        reconnected.send(restart)
                        assertEquals(restart, secondClient.receive())
                        secondClient.send(Signal(SignalType.END, peerCall.id))
                        assertEquals(Signal(SignalType.END, peerCall.id), reconnected.receive())
                    }
                    val ping = UUID.randomUUID()
                    secondClient.send(Signal(SignalType.PING, ping))
                    assertEquals(Signal(SignalType.PONG, ping), secondClient.receive())

                    fun readOverlayProfile(): Activation {
                        val frame = secondClient.receive()
                        assertEquals(SignalType.OVERLAY_PROFILE, frame.type)
                        assertEquals(overlayProfileProbe, frame.id)
                        val profile = Wire.decode(checkNotNull(frame.payload))
                        assertThrows(Exception::class.java) { Activation.read(profile, first.private, a.serial) }
                        return Activation.read(profile, second.private, b.serial)
                    }
                    secondClient.send(Signal(SignalType.PING, overlayProfileProbe))
                    val initial = readOverlayProfile()
                    assertEquals(initialSecret, initial.overlaySecret)
                    host.revokeDevice(a.number)
                    epoch.incrementAndGet()
                    host.refreshOverlayProfiles()
                    val renewed = readOverlayProfile()
                    assertEquals(rotatedSecret, renewed.overlaySecret)
                    assertTrue(renewed.canRefreshOverlay(initial))
                    assertFalse(initial.canRefreshOverlay(renewed))
                    assertFalse(renewed.copy(id = Wire.token()).canRefreshOverlay(initial))
                    secondClient.send(Signal(SignalType.PING, onlineNumbersProbe))
                    assertEquals(
                        Signal(SignalType.ONLINE_NUMBERS, onlineNumbersProbe, "$HOST_NUMBER,${b.number}"),
                        secondClient.receive(),
                    )
                    val newPing = UUID.randomUUID()
                    secondClient.send(Signal(SignalType.PING, newPing))
                    assertEquals(Signal(SignalType.PONG, newPing), secondClient.receive())
                }
            }
            LocalClient("127.0.0.2", Wire.pin(pub), host.localPort).use { client ->
                assertThrows(IOException::class.java) { client.connect(activation(a), first.private) }
            }
        }
    }

    @Test fun transportLifecycleUsesTheRealSocketAndReleasesIt(): Unit = kotlinx.coroutines.runBlocking {
        val public = entry.certificate.publicKey
        val enrolled = allocate(emptyList(), DeviceRequest(first.public, first.public)).copy(expiresAt = null)
        val activation = Activation(null, public, enrolled.number, enrolled.serial, enrolled.activationId)
        val registry = object : EnrollmentRegistry {
            override fun find(number: String) = enrolled.takeIf { it.number == number }
            override fun revoke(number: String) = Unit
        }
        LocalHost(ServerIdentity(entry).tls(), "127.0.0.1", 0, registry, activation.pin).use { master ->
            master.serve({ _, _ -> }, { _, _ -> }, {})
            val client = LocalClient("127.0.0.1", activation.pin, master.localPort)
            val transport = object : LocalSignalingTransport(TransportType.HOTSPOT, client, activation, first.private) {}
            assertEquals(TransportState.IDLE, transport.observeState().value)
            transport.start().getOrThrow()
            assertEquals(TransportState.READY, transport.observeState().value)
            val ping = UUID.randomUUID()
            client.send(Signal(SignalType.PING, ping))
            assertEquals(Signal(SignalType.PONG, ping), client.receive())
            transport.stop().getOrThrow()
            assertEquals(TransportState.IDLE, transport.observeState().value)
            assertThrows(IOException::class.java) { client.send(Signal(SignalType.PING, ping)) }
        }
    }

    @Test fun aRejectedInterfaceCannotReachTlsEnrollment() {
        val public = entry.certificate.publicKey
        val enrolled = allocate(emptyList(), DeviceRequest(first.public, first.public)).copy(expiresAt = null)
        val activation = Activation(null, public, enrolled.number, enrolled.serial, enrolled.activationId)
        val authenticated = LinkedBlockingQueue<String>()
        val registry = object : EnrollmentRegistry {
            override fun find(number: String) = enrolled.takeIf { it.number == number }
            override fun revoke(number: String) = Unit
        }
        LocalHost(ServerIdentity(entry).tls(), "127.0.0.1", 0, registry, activation.pin,
            acceptsLocalPeer = { local, peer -> local == "127.0.0.2" && peer == "127.0.0.1" },
        ).use { master ->
            assertEquals(setOf("127.0.0.1", "127.0.0.2"), master.setDirectAddresses(listOf("127.0.0.1", "127.0.0.2")))
            master.serve({ user, _ -> authenticated.add(user) }, { _, _ -> }, {})
            LocalClient("127.0.0.1", activation.pin, master.localPort).use { rejected ->
                assertThrows(IOException::class.java) { rejected.connect(activation, first.private) }
            }
            assertNull(authenticated.poll())
            LocalClient("127.0.0.2", activation.pin, master.localPort).use { local ->
                local.connect(activation, first.private)
                assertEquals(enrolled.number, authenticated.poll(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun authenticatedLocalDirectoryDeliversAndAcknowledgesQueuedMissedCalls() {
        val hostKey = entry.certificate.publicKey
        val caller = allocate(emptyList(), DeviceRequest(first.public, first.public)).copy(expiresAt = null)
        val callee = allocate(listOf(caller), DeviceRequest(second.public, second.public)).copy(expiresAt = null)
        val pending = mutableMapOf<UUID, Signal>()
        val registry = object : EnrollmentRegistry {
            override fun find(number: String) = listOf(caller, callee).firstOrNull { it.number == number }
            override fun revoke(number: String) = Unit
            override fun enqueueMissed(realm: String, number: String, call: UUID, caller: String) {
                if (number == callee.number) pending[call] = Signal(SignalType.MISSED_CALL, call, "$caller,12345")
            }
            override fun pendingMissed(realm: String, number: String) =
                if (number == callee.number) pending.values.toList() else emptyList()
            override fun ackMissed(realm: String, number: String, call: UUID) {
                if (number == callee.number) pending.remove(call)
            }
        }
        fun activation(user: Enrollment) = Activation(null, hostKey, user.number, user.serial, user.activationId)
        val pin = Wire.pin(hostKey)
        LocalHost(ServerIdentity(entry).tls(), "127.0.0.1", 0, registry, pin).use { host ->
            host.serve({ _, _ -> }, { _, _ -> }, {})
            LocalClient("127.0.0.1", pin, host.localPort).use { source ->
                source.connect(activation(caller), first.private)
                val calls = List(2) { UUID.randomUUID() }
                calls.forEach { id ->
                    source.send(Signal(SignalType.INVITE, id, number = callee.number))
                    assertEquals(Signal(SignalType.UNAVAILABLE, id), source.receive())
                }
                LocalClient("127.0.0.1", pin, host.localPort).use { target ->
                    target.connect(activation(callee), second.private)
                    target.send(Signal(SignalType.PING, missedCallsProbe))
                    repeat(2) {
                        val event = target.receive()
                        assertEquals(SignalType.MISSED_CALL, event.type)
                        assertTrue(event.id in calls)
                        target.send(Signal(SignalType.MISSED_ACK, event.id))
                    }
                    target.send(Signal(SignalType.PING, missedCallsProbe))
                    val ping = UUID.randomUUID()
                    target.send(Signal(SignalType.PING, ping))
                    assertEquals(Signal(SignalType.PONG, ping), target.receive())
                }
            }
        }
    }

    @Test fun localChatQueuesOnlyCiphertextAndDeliversToItsAuthenticatedRecipient() {
        val server = entry.certificate.publicKey
        val pin = Wire.pin(server)
        val source = allocate(emptyList(), DeviceRequest(first.public, first.public)).copy(expiresAt = null)
        val target = allocate(listOf(source), DeviceRequest(second.public, second.public)).copy(expiresAt = null)
        val registry = object : EnrollmentRegistry {
            override fun find(number: String) = listOf(source, target).firstOrNull { it.number == number }
            override fun revoke(number: String) = Unit
        }
        val packets = LinkedBlockingQueue<ChatPacket>()
        val ledger = object : ChatLedger {
            override fun queueChat(packet: ChatPacket): ChatQueueResult {
                packets.add(packet)
                return ChatQueueResult.NEW
            }
            override fun pendingChat(realm: String, number: String) = packets.filter { it.realm == realm && it.recipient == number }
            override fun ackChat(realm: String, number: String, sender: String, id: String) {
                packets.removeIf { it.realm == realm && it.recipient == number && it.sender == sender && it.id == id }
            }
        }
        fun activation(user: Enrollment) = Activation(null, server, user.number, user.serial, user.activationId)
        val chat = LocalChatRelay(pin, registry, ledger, DeviceRequest(first.public, first.public))
        LocalHost(ServerIdentity(entry).tls(), "127.0.0.1", 0, registry, pin, chat = chat).use { host ->
            host.serve({ _, _ -> }, { _, _ -> }, {})
            LocalClient("127.0.0.1", pin, host.localPort).use { sender ->
                sender.connect(activation(source), first.private)
                sender.send(Signal(SignalType.PING, chatProbe))
                assertEquals(Signal(SignalType.CHAT_READY, chatProbe), sender.receive())
                val id = UUID.randomUUID()
                sender.send(Signal(SignalType.CHAT_KEY_REQUEST, id, number = target.number))
                val key = sender.receive()
                assertEquals(SignalType.CHAT_KEY, key.type)
                assertEquals(target.number, key.number)
                val address = ChatAddress(pin, source.number, target.number, id)
                val text = "رسالة WiKall محلية"
                val envelope = ChatEnvelope.seal(address, text, publicKey(checkNotNull(key.payload)), first.private)
                assertFalse(envelope.contains(text))
                val wrongId = UUID.randomUUID()
                sender.send(Signal(SignalType.CHAT_SEND, wrongId, envelope, target.number))
                assertEquals(Signal(SignalType.CHAT_DECLINED, wrongId), sender.receive())
                assertTrue(packets.isEmpty())
                sender.send(Signal(SignalType.CHAT_SEND, id, envelope, target.number))
                assertEquals(Signal(SignalType.CHAT_ACCEPTED, id), sender.receive())
                assertEquals(envelope, packets.single().envelope)
                assertThrows(Exception::class.java) { ChatEnvelope.open(address, envelope, first.public, first.private) }

                sender.send(Signal(SignalType.CHAT_ACK, id, number = source.number))
                val barrier = UUID.randomUUID()
                sender.send(Signal(SignalType.PING, barrier))
                assertEquals(Signal(SignalType.PONG, barrier), sender.receive())
                assertEquals(1, packets.size)

                LocalClient("127.0.0.1", pin, host.localPort).use { recipient ->
                    recipient.connect(activation(target), second.private)
                    recipient.send(Signal(SignalType.PING, chatProbe))
                    assertEquals(Signal(SignalType.CHAT_READY, chatProbe), recipient.receive())
                    val delivery = recipient.receive()
                    assertEquals(SignalType.CHAT_DELIVERY, delivery.type)
                    assertEquals(id, delivery.id)
                    assertEquals(source.number, delivery.number)
                    val payload = checkNotNull(delivery.payload)
                    assertEquals(text, ChatEnvelope.open(address, payload.substringAfter('.'),
                        publicKey(payload.substringBefore('.')), second.private))
                    recipient.send(Signal(SignalType.CHAT_ACK, id, number = source.number))
                    recipient.send(Signal(SignalType.PING, barrier))
                    assertEquals(Signal(SignalType.PONG, barrier), recipient.receive())
                    assertTrue(packets.isEmpty())
                }
            }
        }
    }

    @Test fun losingACalleeDuringRingingProducesOneMissedDelivery() {
        val routes = NumberRouting()
        val id = UUID.randomUUID()
        val from = "99001"
        val to = "99002"
        val invite = Signal(SignalType.INVITE, id, number = to)
        assertNotNull(routes.receive(from, invite) { true })
        val missed = mutableListOf<Pair<UUID, String>>()
        val ended = routes.disconnected(to) { lost, caller -> missed.add(lost to caller) }
        assertEquals(Signal(SignalType.END, id), ended?.message)
        assertNull(routes.disconnected(to) { lost, caller -> missed.add(lost to caller) })
        assertNull(routes.receive(from, invite) { true })
        assertEquals(listOf(id to from), missed)
    }

    @Test fun routesEachNumberAndRejectsThirdPartyCallSignals() {
        val routing = NumberRouting()
        val a = "99001"
        val b = "99002"
        val callId = UUID.randomUUID()
        val call = Signal(SignalType.INVITE, callId, number = b)
        assertEquals(RoutedSignal(a, b, call.copy(number = a)), routing.receive(a, call) { true })
        assertNull(routing.receive(HOST_NUMBER, Signal(SignalType.END, callId)) { true })
        assertNull(routing.receive(a, Signal(SignalType.ACCEPT, callId)) { true })
        assertNull(routing.receive(a, Signal(SignalType.OFFER, callId, "called too early")) { true })
        assertEquals(SignalType.BUSY, routing.receive(HOST_NUMBER, call.copy(id = UUID.randomUUID())) { true }?.message?.type)
        assertEquals(a, routing.receive(b, Signal(SignalType.ACCEPT, callId)) { true }?.to)
        assertEquals(b, routing.receive(a, Signal(SignalType.OFFER, callId, "audio")) { true }?.to)
        assertEquals(Signal(SignalType.END, callId), routing.disconnected(b)?.message)
        assertNull(routing.receive(a, call) { true })
        val absent = call.copy(id = UUID.randomUUID())
        assertEquals(SignalType.UNAVAILABLE, routing.receive(a, absent) { false }?.message?.type)
    }

    @org.junit.Ignore("Requires internet — future phase")
    @Test fun internetCallingIsOutsideTheOfflineBuild() = Unit

    @Test fun authenticatedFrameSequenceRejectsDuplicatesAndTimeRegression() {
        var now = 20L
        val sender = SequencedFrames { now }
        val receiver = SequencedFrames { 700L }
        val first = sender.encode("payload")
        assertEquals("payload", receiver.decode(first))
        assertThrows(IllegalArgumentException::class.java) { receiver.decode(first) }
        now = 35L
        assertEquals("new", receiver.decode(sender.encode("new")))
        assertThrows(IllegalArgumentException::class.java) { receiver.decode("5|3|1|old timestamp") }
        assertThrows(IllegalArgumentException::class.java) { receiver.decode("5|4|99|skipped sequence") }
    }

    @Test fun localOnlyIceRejectsTurnAndPublicHosts() {
        val relay = "candidate:1 1 UDP 1 203.0.113.10 45000 typ relay"
        val publicHost = "candidate:1 1 UDP 1 203.0.113.10 45000 typ host"
        assertFalse(lanIce(relay))
        assertFalse(lanIce(publicHost))
        assertFalse(audioOnly("v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=$relay"))
    }

    @Test fun activationWithoutAddressCanBeReadAndExpiredActivationIsRejected() {
        val request = DeviceRequest(first.public, second.public)
        val device = allocate(emptyList(), request)
        val activation = Activation(null, entry.certificate.publicKey, device.number, device.serial, device.activationId,
            expiresAt = device.expiresAt)
        val current = activation.export(entry.privateKey, second.public)
        assertEquals(activation, Activation.read(current, second.private, device.serial))
        val expired = activation.copy(expiresAt = 1).export(entry.privateKey, second.public)
        assertThrows(IllegalArgumentException::class.java) { Activation.read(expired, second.private, device.serial) }
    }

    @Test fun localFailoverOrdersLanThenHotspotThenOverlay() {
        val source = java.net.InetAddress.getByName("192.168.1.2")
        val lan = RouteCandidate("192.168.1.4", LocalPath.LAN_DIRECT, source, null)
        val hotspot = RouteCandidate("192.168.1.9", LocalPath.HOTSPOT_DIRECT, source, null)
        val overlay = RouteCandidate(OVERLAY_HOST, LocalPath.OVERLAY_LOCAL, source, null)
        assertEquals(listOf(lan, hotspot, overlay), prioritizeRoutes(listOf(overlay, hotspot, lan, lan)))
    }

    @Test fun mediaRequiresAnAcceptedInvitationAndOnlySupportsLanAudio() {
        val session = LocalCall()
        val invitation = requireNotNull(session.dial())
        session.media(CallState.CONNECTED)
        assertEquals(CallState.CALLING, session.state)
        session.remote(Signal(SignalType.ACCEPT, invitation.id))
        session.media(CallState.CONNECTING)
        assertEquals(CallState.CONNECTING, session.state)

        assertTrue(lanIce("candidate:1 1 udp 1 192.168.1.50 51500 typ host"))
        assertFalse(lanIce("candidate:1 1 udp 1 8.8.8.8 51500 typ host"))
        assertFalse(lanIce("candidate:1 1 udp 1 192.168.1.50 51500 typ relay"))
        assertTrue(audioOnly("v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"))
        assertFalse(audioOnly("m=audio 9 UDP/TLS/RTP/SAVPF 111\r\nm=video 9 UDP/TLS/RTP/SAVPF 96"))
    }

    companion object {
        private lateinit var directory: File
        private lateinit var fixture: File
        private lateinit var first: KeyPair
        private lateinit var second: KeyPair

        private fun keys(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        @JvmStatic
        @BeforeClass
        fun createFixture() {
            directory = Files.createTempDirectory("wi-call-tests").toFile()
            fixture = File(directory, "fixture.p12")
            first = keys()
            second = keys()
            val keytool = File(System.getProperty("java.home"), "bin/keytool")
            val process = ProcessBuilder(
                keytool.path, "-genkeypair", "-noprompt", "-alias", "fixture", "-dname", "CN=Wi-Call Test",
                "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", fixture.path, "-storepass", "fixture-only",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { output }
        }

        @JvmStatic
        @AfterClass
        fun deleteFixture() {
            if (::directory.isInitialized) directory.deleteRecursively()
        }
    }
}
