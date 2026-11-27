package com.mokasas.wikall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class WikallVpnService : VpnService() {
    @Inject internal lateinit var controller: ConnectionController
    private var current: OverlayRequest? = null
    private var tunnel: ParcelFileDescriptor? = null
    private var engineStarted = false
    private val engine = EasyTierEngine()

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
        if (request == current && tunnel != null) return START_NOT_STICKY

        val previous = current
        val keepTunnel = previous != null && previous.address == request.address && previous.pin == request.pin && tunnel != null
        if (keepTunnel) stopEngine() else tearDown()
        current = request
        val started = runCatching {
            val monitor = NetworkStateMonitor(this)
            check(!monitor.overlayConflictsWithLocalSubnet())
            engine.start(request.configuration())
            engineStarted = true
            val physical = monitor.localLinks().map { it.first }.distinct()
            val descriptor = tunnel ?: checkNotNull(Builder()
                .setSession("WiKall")
                .setMtu(1280)
                .addAddress(request.address, OVERLAY_PREFIX)
                .addRoute(OVERLAY_NETWORK, OVERLAY_PREFIX)
                .addAllowedApplication(packageName)
                .establish()).also { tunnel = it }
            setUnderlyingNetworks(physical.takeIf { it.isNotEmpty() }?.toTypedArray())
            engine.attachTun(OVERLAY_INSTANCE, descriptor.fd)
        }.isSuccess

        controller.overlayChanged(request, if (started) request.address else null)
        if (!started) {
            tearDown()
            stopSelf(startId)
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
        super.onDestroy()
    }

    private fun stopEngine() {
        if (engineStarted) runCatching { engine.stopAll() }
        engineStarted = false
    }

    private fun tearDown() {
        stopEngine()
        runCatching { tunnel?.close() }
        tunnel = null
        current = null
    }

    private companion object {
        const val CHANNEL = "wikall-overlay"
        const val NOTIFICATION_ID = 703
    }
}
