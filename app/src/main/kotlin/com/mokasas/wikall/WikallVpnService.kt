package com.mokasas.wikall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class WikallVpnService : VpnService() {
    @Inject internal lateinit var controller: ConnectionController
    private var current: OverlayRequest? = null
    private var tunnel: ParcelFileDescriptor? = null
    private var attachedPlan: VirtualAddressPlan? = null
    private var addressDeadline = 0L
    private var unknownSamples = 0
    @Inject internal lateinit var engine: OverlayEngine
    private val main = Handler(Looper.getMainLooper())
    private val lifecycle = VpnLifecycleQueue()
    // Android callbacks and controller state are confined to the main thread.
    private var requested: OverlayRequest? = null
    private var destroyed = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Android can keep a VpnService bound while its TUN is active.
            // Explicitly release the native engine and descriptor before stopping.
            // A queued stop must not tear down a newly requested session.
            if (controller.overlayRequest == null) {
                android.util.Log.i(LOG_TAG, "vpn_stop_requested")
                stopSession(startId)
            }
            return START_NOT_STICKY
        }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "شبكة WiKall", NotificationManager.IMPORTANCE_LOW))
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("WiKall")
            .setContentText("عقدة الشبكة التراكبية المحلية")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build())

        val request = controller.overlayRequest
        if (request == null) {
            stopSession(startId)
            return START_NOT_STICKY
        }
        if (requested == request) return START_NOT_STICKY
        requested = request
        controller.overlayAwaitingAddress(request)
        lifecycle.replace { command -> startSession(request, command) }
        return START_NOT_STICKY
    }

    private fun startSession(request: OverlayRequest, command: Long, allowTunnelReuse: Boolean = true) {
        val keepTunnel = allowTunnelReuse && tunnel != null && current?.canReuseTunnelFor(request) == true
        val stopped = if (keepTunnel) stopEngine() else tearDown()
        if (!lifecycle.isCurrent(command)) return
        if (!stopped) {
            failRequest(request, command)
            return
        }
        current = request
        unknownSamples = 0
        val started = runCatching {
            check(!NetworkStateMonitor(this).overlayConflictsWithLocalSubnet()) {
                "local_subnet_conflict"
            }
            // A failed start may still have registered native resources.
            engineToStop = engine
            engine.start(request)
            android.util.Log.i(LOG_TAG, "native_start_requested")
        }.onFailure {
            val category = if (it.message == "local_subnet_conflict") "local_subnet_conflict"
                else nativeFailureCategory(it.message) ?: "unknown"
            android.util.Log.w(LOG_TAG, "native_start_failed category=$category")
        }.isSuccess
        if (started) {
            addressDeadline = SystemClock.elapsedRealtime() + ADDRESS_TIMEOUT_MS
            scheduleHealth(request, command, 0L)
        } else {
            failRequest(request, command)
        }
    }

    private fun stopSession(startId: Int? = null, revoke: Boolean = false) {
        val request = requested
        requested = null
        lifecycle.replace { command ->
            tearDown()
            main.post {
                if (!lifecycle.isCurrent(command) || destroyed) return@post
                if (revoke && request != null) controller.overlayChanged(request, null)
                if (startId != null && !stopSelfResult(startId)) return@post
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (startId == null) stopSelf()
            }
        }
    }

    override fun onRevoke() {
        main.post {
            if (!destroyed) stopSession(revoke = true)
        }
    }

    override fun onDestroy() {
        destroyed = true
        val request = requested
        lifecycle.close { tearDown() }
        if (request != null) controller.overlayChanged(request, null)
        super.onDestroy()
    }

    private fun publish(request: OverlayRequest, command: Long, action: () -> Unit) {
        main.post {
            if (lifecycle.isCurrent(command) && controller.overlayRequest == request) action()
        }
    }

    private fun scheduleHealth(request: OverlayRequest, command: Long, delay: Long) {
        lifecycle.poll(command, delay) {
            val snapshot = runCatching { engine.snapshot(OVERLAY_INSTANCE) }
                .onFailure { android.util.Log.w(LOG_TAG, "native_snapshot_failed") }.getOrNull()
            if (lifecycle.isCurrent(command)) applySnapshot(request, command, snapshot)
        }
    }

    private fun applySnapshot(request: OverlayRequest, command: Long, snapshot: EasyTierSnapshot?) {
        val plan = nativeAddressPlan(snapshot, request)
        if (snapshot?.running == false) {
            android.util.Log.w(LOG_TAG, "native_instance_stopped category=${snapshot.failureCategory ?: "unknown"}")
            failRequest(request, command)
            return
        }
        if (plan == null) {
            // A single missing diagnostic after attachment may be transient.
            if (snapshot?.running == null && attachedPlan != null && unknownSamples++ < 2 &&
                addressDeadline == 0L) {
                publish(request, command) { controller.overlayHealthChanged(request, OverlayHealth(OverlayHealthState.UNKNOWN)) }
                scheduleHealth(request, command, HEALTH_INTERVAL_MS)
                return
            }
            if (attachedPlan != null) {
                rebuildTunnel(request, command)
                return
            }
            // A provisioned mobile Node can listen/advertise without a virtual IP.
            // EasyTier DHCP waits for peers. Do not kill a healthy engine just because
            // the phone is temporarily alone; do not claim the TUN is ready either.
            if (mayRemainBootstrapNode(request, snapshot)) {
                publish(request, command) { controller.overlayListeningWithoutAddress(request) }
                addressDeadline = 0L
                scheduleHealth(request, command, HEALTH_INTERVAL_MS)
                return
            }
            if (addressDeadline == 0L) addressDeadline = SystemClock.elapsedRealtime() + ADDRESS_TIMEOUT_MS
            if (SystemClock.elapsedRealtime() >= addressDeadline) {
                android.util.Log.w(LOG_TAG, if (snapshot == null) "native_instance_not_reported"
                    else if (snapshot.address == null) "native_address_not_reported category=${snapshot.failureCategory ?: "none"}"
                    else "native_address_policy_rejected prefix=${snapshot.prefix} running=${snapshot.running}")
                failRequest(request, command)
            } else {
                scheduleHealth(request, command, ADDRESS_POLL_MS)
            }
            return
        }
        if (attachedPlan != null && attachedPlan != plan) {
            rebuildTunnel(request, command)
            return
        }
        unknownSamples = 0
        val attachRequired = addressDeadline != 0L || attachedPlan != plan || tunnel == null
        if (attachRequired) {
            val attached = runCatching {
                val monitor = NetworkStateMonitor(this)
                check(!monitor.overlayConflictsWithLocalSubnet())
                val physical = monitor.localLinks().map { it.first }.distinct()
                android.util.Log.i(LOG_TAG, "native_address_accepted prefix=${plan.prefix} physical_links=${physical.size}")
                val descriptor = tunnel ?: checkNotNull(Builder()
                    .setSession("WiKall")
                    .setMtu(1280)
                    .addAddress(plan.address, plan.prefix)
                    .addRoute(plan.network, plan.prefix)
                    .addAllowedApplication(packageName)
                    .establish()).also { tunnel = it }
                android.util.Log.i(LOG_TAG, "android_tun_established")
                val underlyingAccepted = setUnderlyingNetworks(physical.takeIf { it.isNotEmpty() }?.toTypedArray())
                android.util.Log.i(LOG_TAG, "underlying_networks_accepted=$underlyingAccepted")
                check(lifecycle.isCurrent(command))
                engine.attachTun(OVERLAY_INSTANCE, descriptor.fd)
                android.util.Log.i(LOG_TAG, "native_tun_attach_requested")
                attachedPlan = plan
            }.onFailure {
                android.util.Log.w(LOG_TAG, "tun_establish_or_attach_failed category=${nativeFailureCategory(it.message) ?: "unknown"}")
            }.isSuccess
            if (!attached) {
                failRequest(request, command)
                return
            }
            addressDeadline = 0L
            publish(request, command) { controller.overlayChanged(request, plan.address) }
        }
        val health = assessOverlayHealth(snapshot, plan.address, plan.prefix)
        publish(request, command) { controller.overlayHealthChanged(request, health) }
        scheduleHealth(request, command, HEALTH_INTERVAL_MS)
    }

    private fun rebuildTunnel(request: OverlayRequest, command: Long) {
        // EasyTier borrows this fd asynchronously. Stop its reader before
        // closing the descriptor or opening a replacement with a reused fd.
        publish(request, command) { controller.overlayAwaitingAddress(request) }
        startSession(request, command, allowTunnelReuse = false)
    }

    private fun closeTunnel() {
        val descriptor = tunnel
        runCatching { descriptor?.close() }
            .onFailure { android.util.Log.w(LOG_TAG, "android_tun_close_failed") }
        if (descriptor != null) android.util.Log.i(LOG_TAG, "android_tun_close_requested")
        tunnel = null
        attachedPlan = null
    }

    private fun failRequest(request: OverlayRequest, command: Long) {
        tearDown()
        publish(request, command) {
            requested = null
            controller.overlayChanged(request, null)
            stopSelf()
        }
    }

    private fun stopEngine(): Boolean {
        val active = engineToStop ?: return true
        return runCatching { active.stopAll() }
            .onSuccess { engineToStop = null }
            .onFailure { android.util.Log.w(LOG_TAG, "native_stop_failed category=${nativeFailureCategory(it.message) ?: "unknown"}") }
            .isSuccess
    }

    private fun tearDown(): Boolean {
        val stopped = stopEngine()
        closeTunnel()
        addressDeadline = 0L
        unknownSamples = 0
        current = null
        return stopped
    }

    companion object {
        // Accessed only by the process VPN worker. Preserve a failed cleanup
        // across service recreation so a replacement cannot start over it.
        private var engineToStop: OverlayEngine? = null

        internal const val ACTION_STOP = "com.mokasas.wikall.action.STOP_LOCAL_VPN"
        private const val LOG_TAG = "WiKallVpn"
        const val CHANNEL = "wikall-overlay"
        const val NOTIFICATION_ID = 703
        const val HEALTH_INTERVAL_MS = 5_000L
        const val ADDRESS_POLL_MS = 500L
        const val ADDRESS_TIMEOUT_MS = 20_000L
    }
}
