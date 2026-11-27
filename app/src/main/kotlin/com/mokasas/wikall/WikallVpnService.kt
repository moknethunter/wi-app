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
import java.util.concurrent.Executors
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class WikallVpnService : VpnService() {
    @Inject internal lateinit var controller: ConnectionController
    private var current: OverlayRequest? = null
    private var tunnel: ParcelFileDescriptor? = null
    private var engineStarted = false
    private var attachedPlan: VirtualAddressPlan? = null
    private var addressDeadline = 0L
    private var unknownSamples = 0
    private val engine = EasyTierEngine()
    private val main = Handler(Looper.getMainLooper())
    private val diagnostics = Executors.newSingleThreadExecutor()
    private var healthGeneration = 0L
    private var healthTask: Runnable? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (request == current && engineStarted) return START_NOT_STICKY

        val previous = current
        val keepTunnel = previous != null && previous.pin == request.pin &&
            previous.number == request.number && previous.secret == request.secret && tunnel != null
        if (keepTunnel) stopEngine() else tearDown()
        current = request
        controller.overlayAwaitingAddress(request)
        val started = runCatching {
            check(!NetworkStateMonitor(this).overlayConflictsWithLocalSubnet())
            engine.start(request.configuration())
            engineStarted = true
        }.isSuccess
        if (started) {
            addressDeadline = SystemClock.elapsedRealtime() + ADDRESS_TIMEOUT_MS
            scheduleHealth(request, healthGeneration, 0L)
        } else {
            failRequest(request)
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopSelf()
    }

    override fun onDestroy() {
        val request = current
        tearDown()
        if (request != null) controller.overlayChanged(request, null)
        diagnostics.shutdownNow()
        super.onDestroy()
    }

    private fun scheduleHealth(request: OverlayRequest, generation: Long, delay: Long) {
        healthTask = Runnable {
            healthTask = null
            if (generation != healthGeneration || current != request || !engineStarted) return@Runnable
            diagnostics.execute {
                val snapshot = runCatching { engine.snapshot(OVERLAY_INSTANCE) }.getOrNull()
                main.post {
                    if (generation == healthGeneration && current == request && engineStarted &&
                        controller.overlayRequest == request) {
                        applySnapshot(request, generation, snapshot)
                    }
                }
            }
        }.also { main.postDelayed(it, delay) }
    }

    private fun applySnapshot(request: OverlayRequest, generation: Long, snapshot: EasyTierSnapshot?) {
        val plan = nativeAddressPlan(snapshot, request)
        if (snapshot?.running == false) {
            failRequest(request)
            return
        }
        if (plan == null) {
            // A single missing diagnostic after attachment may be transient.
            if (snapshot?.running == null && attachedPlan != null && unknownSamples++ < 2 &&
                addressDeadline == 0L) {
                controller.overlayHealthChanged(request, OverlayHealth(OverlayHealthState.UNKNOWN))
                scheduleHealth(request, generation, HEALTH_INTERVAL_MS)
                return
            }
            if (attachedPlan != null) {
                closeTunnel()
                controller.overlayAwaitingAddress(request)
            }
            if (addressDeadline == 0L) addressDeadline = SystemClock.elapsedRealtime() + ADDRESS_TIMEOUT_MS
            if (SystemClock.elapsedRealtime() >= addressDeadline) {
                failRequest(request)
            } else {
                scheduleHealth(request, generation, ADDRESS_POLL_MS)
            }
            return
        }
        unknownSamples = 0
        val attachRequired = addressDeadline != 0L || attachedPlan != plan || tunnel == null
        if (attachRequired) {
            val attached = runCatching {
                val monitor = NetworkStateMonitor(this)
                check(!monitor.overlayConflictsWithLocalSubnet())
                if (attachedPlan != plan) closeTunnel()
                val physical = monitor.localLinks().map { it.first }.distinct()
                val descriptor = tunnel ?: checkNotNull(Builder()
                    .setSession("WiKall")
                    .setMtu(1280)
                    .addAddress(plan.address, plan.prefix)
                    .addRoute(plan.network, plan.prefix)
                    .addAllowedApplication(packageName)
                    .establish()).also { tunnel = it }
                setUnderlyingNetworks(physical.takeIf { it.isNotEmpty() }?.toTypedArray())
                engine.attachTun(OVERLAY_INSTANCE, descriptor.fd)
                attachedPlan = plan
            }.isSuccess
            if (!attached) {
                failRequest(request)
                return
            }
            addressDeadline = 0L
            controller.overlayChanged(request, plan.address)
        }
        controller.overlayHealthChanged(request, assessOverlayHealth(snapshot, plan.address, plan.prefix))
        scheduleHealth(request, generation, HEALTH_INTERVAL_MS)
    }

    private fun closeTunnel() {
        runCatching { tunnel?.close() }
        tunnel = null
        attachedPlan = null
    }

    private fun failRequest(request: OverlayRequest) {
        tearDown()
        controller.overlayChanged(request, null)
        stopSelf()
    }

    private fun stopHealthPolling() {
        healthGeneration++
        healthTask?.let(main::removeCallbacks)
        healthTask = null
    }

    private fun stopEngine() {
        stopHealthPolling()
        if (engineStarted) runCatching { engine.stopAll() }
        engineStarted = false
    }

    private fun tearDown() {
        stopEngine()
        closeTunnel()
        addressDeadline = 0L
        unknownSamples = 0
        current = null
    }

    private companion object {
        const val CHANNEL = "wikall-overlay"
        const val NOTIFICATION_ID = 703
        const val HEALTH_INTERVAL_MS = 5_000L
        const val ADDRESS_POLL_MS = 500L
        const val ADDRESS_TIMEOUT_MS = 20_000L
    }
}
