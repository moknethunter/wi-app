package com.mokasas.wikall

import android.content.Context
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import livekit.org.webrtc.AudioSource
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.audio.JavaAudioDeviceModule

internal data class MediaPathSample(
    val localIp: String,
    val remoteIp: String,
    val roundTripMs: Long?,
    val lossFraction: Double?,
    val jitterMs: Double?,
)

internal class VoiceMedia(
    private val context: Context,
    private val send: (SignalType, String) -> Unit,
    private val onState: (CallState) -> Unit,
    private val onFailure: () -> Unit,
    private val localAddresses: () -> List<String>,
    private val allowsRemoteAddress: (String) -> Boolean = ::privateIpv4,
    private val onIceState: (String) -> Unit = {},
    private val onStats: (MediaPathSample) -> Unit = {},
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var oldMode: Int? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var closed = false
    private val remoteTracks = mutableSetOf<AudioTrack>()
    private var focused = true
    private var muted = false
    private var restarting = false
    private var remoteDescription: String? = null
    private var statsTask: Runnable? = null
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener({ change ->
            focused = change == AudioManager.AUDIOFOCUS_GAIN
            applyFocus()
        }, main).build()
    private var remoteReady = false
    private var localReady = false
    private var settingRemote = false
    private val remoteIce = ArrayDeque<IceCandidate>()
    private val localIce = ArrayDeque<String>()

    fun start(outgoing: Boolean) {
        check(peer == null)
        oldMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        focused = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        val device = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        val engine = try {
            val options = PeerConnectionFactory.Options().apply {
                // Android's network monitor can omit the downstream interface of a phone hotspot.
                // Enumerate interfaces directly for local ICE, which still publishes only private IPv4.
                disableNetworkMonitor = true
                networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR or
                    PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK
            }
            PeerConnectionFactory.builder().setOptions(options).setAudioDeviceModule(device).createPeerConnectionFactory()
        } finally {
            device.release()
        }
        factory = engine
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }
        val connection = checkNotNull(engine.createPeerConnection(config, observer))
        peer = connection
        val voiceSource = engine.createAudioSource(MediaConstraints()).also { source = it }
        val microphone = engine.createAudioTrack("wi-call-microphone", voiceSource).also { track = it }
        connection.addTrack(microphone, listOf("wi-call"))
        applyFocus()
        if (outgoing) connection.createOffer(createSdp(SignalType.OFFER), MediaConstraints())
    }

    fun receive(type: SignalType, payload: String) {
        val connection = peer ?: return
        when (type) {
            SignalType.OFFER, SignalType.ANSWER -> {
                if (settingRemote || remoteDescription == payload || !audioOnly(payload, ::validCandidate)) return
                if (remoteReady && type == SignalType.ANSWER) return
                if (remoteReady && type == SignalType.OFFER) {
                    localReady = false
                    localIce.clear()
                }
                settingRemote = true
                val sdpType = if (type == SignalType.OFFER) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
                connection.setRemoteDescription(setSdp {
                    settingRemote = false
                    remoteReady = true
                    remoteDescription = payload
                    while (remoteIce.isNotEmpty()) connection.addIceCandidate(remoteIce.removeFirst())
                    if (type == SignalType.OFFER) connection.createAnswer(createSdp(SignalType.ANSWER), MediaConstraints())
                }, SessionDescription(sdpType, payload))
            }
            SignalType.ICE -> {
                val candidate = runCatching { decodeIce(payload) }.getOrNull() ?: return
                if (remoteReady) connection.addIceCandidate(candidate)
                else if (remoteIce.size < ICE_LIMIT) remoteIce.add(candidate)
            }
            else -> Unit
        }
    }

    fun canRestart(): Boolean = !restarting && !settingRemote &&
        peer?.signalingState() == PeerConnection.SignalingState.STABLE

    fun restart(outgoing: Boolean) {
        val connection = peer ?: return
        if (!canRestart()) return
        restarting = true
        remoteReady = false
        localReady = false
        remoteIce.clear()
        localIce.clear()
        if (outgoing) {
            connection.restartIce()
            connection.createOffer(createSdp(SignalType.OFFER), MediaConstraints())
        }
    }

    fun speaker(enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 31) {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = enabled
            return true
        }
        if (!enabled) {
            audio.clearCommunicationDevice()
            return true
        }
        val device = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        return device != null && audio.setCommunicationDevice(device)
    }

    fun mute(muted: Boolean) {
        this.muted = muted
        applyFocus()
    }

    private fun applyFocus() {
        track?.setEnabled(focused && !muted)
        remoteTracks.forEach { it.setEnabled(focused) }
    }

    private fun useRemote(receiver: RtpReceiver?) {
        val remote = receiver?.track() as? AudioTrack ?: return
        if (remoteTracks.add(remote)) applyFocus()
    }

    private fun validCandidate(candidate: String) =
        lanIce(candidate) && allowsRemoteAddress(candidate.split(' ')[4])

    private fun allowedLocalCandidate(candidate: String): Boolean {
        if (!lanIce(candidate)) return false
        return candidate.split(' ')[4] in localAddresses()
    }

    override fun close() {
        if (closed) return
        closed = true
        stopSampling()
        track?.setEnabled(false)
        remoteTracks.clear()
        if (Build.VERSION.SDK_INT >= 31 && oldMode != null) audio.clearCommunicationDevice()
        audio.abandonAudioFocusRequest(focus)
        peer?.dispose()
        track?.dispose()
        source?.dispose()
        factory?.dispose()
        oldMode?.let { audio.mode = it }
    }

    private fun startSampling() {
        stopSampling()
        statsTask = Runnable {
            statsTask = null
            val connection = peer ?: return@Runnable
            connection.getStats { report ->
                active {
                    val stats = report.statsMap
                    val transport = stats.values.firstOrNull { it.type == "transport" }
                    val pair = stats[transport?.members?.get("selectedCandidatePairId") as? String]?.members
                    val local = stats[pair?.get("localCandidateId") as? String]?.members?.get("address") as? String
                    val remote = stats[pair?.get("remoteCandidateId") as? String]?.members?.get("address") as? String
                    val inbound = stats.values.firstOrNull { it.type == "inbound-rtp" }?.members
                    val received = (inbound?.get("packetsReceived") as? Number)?.toDouble()
                    val lost = (inbound?.get("packetsLost") as? Number)?.toDouble()?.coerceAtLeast(0.0)
                    val loss = if (received != null && lost != null && received + lost > 0) lost / (received + lost) else null
                    if (local != null && remote != null) {
                        onStats(MediaPathSample(local, remote,
                            (pair?.get("currentRoundTripTime") as? Number)?.toDouble()?.times(1000)?.toLong(),
                            loss, (inbound?.get("jitter") as? Number)?.toDouble()?.times(1000)))
                    }
                    if (connection.connectionState() == PeerConnection.PeerConnectionState.CONNECTED) startSampling()
                }
            }
        }.also { main.postDelayed(it, 5_000L) }
    }

    private fun stopSampling() {
        statsTask?.let(main::removeCallbacks)
        statsTask = null
    }

    private fun active(action: () -> Unit) {
        main.post { if (!closed) action() }
    }

    private fun createSdp(type: SignalType) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = active {
            val sanitized = description.description.lineSequence()
                .filterNot { it.startsWith("a=candidate:") && !allowedLocalCandidate(it.removePrefix("a=")) }
                .joinToString("\r\n")
            if (!audioOnly(sanitized, ::allowedLocalCandidate)) {
                onFailure()
                return@active
            }
            peer?.setLocalDescription(setSdp {
                send(type, sanitized)
                localReady = true
                while (localIce.isNotEmpty()) send(SignalType.ICE, localIce.removeFirst())
            }, description)
        }
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(reason: String?) = active(onFailure)
        override fun onSetFailure(reason: String?) = active(onFailure)
    }

    private fun setSdp(success: () -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = active(success)
        override fun onCreateFailure(reason: String?) = active(onFailure)
        override fun onSetFailure(reason: String?) = active(onFailure)
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = active {
            onIceState(state?.name.orEmpty())
        }
        override fun onTrack(transceiver: RtpTransceiver?) = active { useRemote(transceiver?.receiver) }
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = active { useRemote(receiver) }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) = active {
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    restarting = false
                    onState(CallState.CONNECTED)
                    startSampling()
                }
                PeerConnection.PeerConnectionState.DISCONNECTED, PeerConnection.PeerConnectionState.FAILED -> {
                    stopSampling()
                    onState(CallState.RECONNECTING)
                }
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = active {
            if (!allowedLocalCandidate(candidate.sdp)) return@active
            val payload = buildJsonObject {
                put("mid", candidate.sdpMid)
                put("index", candidate.sdpMLineIndex)
                put("sdp", candidate.sdp)
            }.toString()
            if (localReady) send(SignalType.ICE, payload)
            else if (localIce.size < ICE_LIMIT) localIce.add(payload)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private fun decodeIce(payload: String): IceCandidate {
        require(payload.length <= 1200)
        val objectValue = Json.parseToJsonElement(payload).jsonObject
        val mid = objectValue.getValue("mid").jsonPrimitive.content
        val index = objectValue.getValue("index").jsonPrimitive.int
        val sdp = objectValue.getValue("sdp").jsonPrimitive.content
        require(index == 0 && mid.length in 1..16 && validCandidate(sdp))
        return IceCandidate(mid, index, sdp)
    }

    private companion object {
        const val ICE_LIMIT = 32
    }
}

internal fun lanIce(candidate: String): Boolean {
    if (candidate.length > 800 || '\r' in candidate || '\n' in candidate || !candidate.startsWith("candidate:")) return false
    val fields = candidate.split(' ')
    return fields.size >= 8 && fields[1] == "1" && fields[2].equals("udp", ignoreCase = true) &&
        privateIpv4(fields[4]) && (fields[5].toIntOrNull() ?: 0) in 1..65535 &&
        fields[6] == "typ" && fields[7] == "host"
}

internal fun audioOnly(sdp: String, candidateAllowed: (String) -> Boolean = ::lanIce): Boolean {
    if (sdp.length > 11_000) return false
    val lines = sdp.lineSequence().map(String::trimEnd).toList()
    val media = lines.filter { it.startsWith("m=") }
    return media.size == 1 && media.single().startsWith("m=audio ") &&
        lines.filter { it.startsWith("a=candidate:") }.all { candidateAllowed(it.removePrefix("a=")) }
}
