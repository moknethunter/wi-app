package com.mokasas.wikall

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.HiltAndroidApp
import java.util.UUID
import javax.inject.Inject

@HiltAndroidApp
class WiCallApp : Application()

@AndroidEntryPoint
class LocalServerForegroundService : Service() {
    @Inject internal lateinit var controller: ConnectionController
    private val notifications get() = getSystemService(NotificationManager::class.java)
    private val vibrator get() = getSystemService(Vibrator::class.java)
    private var ringtone: Ringtone? = null
    private var last: SessionState? = null
    private var ringingId: UUID? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(
            NotificationChannel(NETWORK_CHANNEL, "خدمة Wi-Call", NotificationManager.IMPORTANCE_LOW)
        )
        notifications.createNotificationChannel(
            NotificationChannel(INCOMING_CHANNEL, "المكالمات الواردة", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
            }
        )
        startForeground(NETWORK_ID, ongoing(controller.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        controller.onUpdate = ::update
        update(controller.state.value)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                controller.stop()
                stopSelf()
            }
            ACTION_REJECT -> {
                val state = controller.state.value
                if (state.callState == CallState.RINGING && state.callId?.toString() == intent.getStringExtra(CALL_ID)) {
                    controller.decide(SignalType.REJECT)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        controller.onUpdate = null
        controller.stop()
        stopRinging()
        super.onDestroy()
    }

    private fun update(state: SessionState) {
        val old = last
        last = state
        if (old == null || old.mode != state.mode || old.status != state.status || old.callState != state.callState) {
            notifications.notify(NETWORK_ID, ongoing(state))
        }
        val incoming = state.callId?.takeIf { state.callState == CallState.RINGING }
        if (incoming != ringingId) {
            stopRinging()
            if (incoming != null) startRinging(incoming, state.peer.orEmpty())
        }
        if (state.callId == null && old?.callId != null) stopService(Intent(this, CallSessionService::class.java))
    }

    private fun ongoing(state: SessionState): Notification {
        val message = when (state.callState) {
            CallState.ACCEPTED -> "افتح التطبيق لتشغيل الصوت."
            CallState.CONNECTING, CallState.RECONNECTING -> "جارٍ إعداد المكالمة."
            CallState.CONNECTED -> "مكالمة صوتية."
            else -> state.status.ifBlank { "Wi-Call جاهز للاتصال." }
        }
        val stop = PendingIntent.getService(
            this, 11, Intent(this, LocalServerForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, NETWORK_CHANNEL)
            .setSmallIcon(R.drawable.ic_wikall)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setContentIntent(openApp(this))
            .addAction(Notification.Action.Builder(null, "قطع الاتصال", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun startRinging(id: UUID, peer: String) {
        if (!notifications.areNotificationsEnabled()) return
        val channel = notifications.getNotificationChannel(INCOMING_CHANNEL)
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return
        ringingId = id
        val open = openApp(this, incoming = true)
        val reject = PendingIntent.getService(
            this, 12, Intent(this, LocalServerForegroundService::class.java).setAction(ACTION_REJECT)
                .putExtra(CALL_ID, id.toString()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val display = Notification.Builder(this, INCOMING_CHANNEL)
            .setSmallIcon(R.drawable.ic_wikall)
            .setContentTitle("مكالمة واردة · Wi-Call")
            .setContentText("المستخدم $peer")
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "عرض المكالمة", open).build())
            .addAction(Notification.Action.Builder(null, "رفض", reject).build())
        if (Build.VERSION.SDK_INT < 34 || notifications.canUseFullScreenIntent()) {
            display.setFullScreenIntent(open, true)
        }
        notifications.notify(INCOMING_ID, display.build())
        if (channel.importance < NotificationManager.IMPORTANCE_HIGH ||
            notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        runCatching {
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            ringtone?.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
            ringtone?.isLooping = true
            ringtone?.play()
        }
        runCatching {
            if (getSystemService(android.media.AudioManager::class.java).ringerMode != android.media.AudioManager.RINGER_MODE_SILENT) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 450, 600), 0),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
                )
            }
        }
    }

    private fun stopRinging() {
        if (ringingId == null) return
        ringingId = null
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        notifications.cancel(INCOMING_ID)
    }

    private companion object {
        const val NETWORK_CHANNEL = "wi-call-network"
        const val INCOMING_CHANNEL = "wi-call-incoming"
        const val NETWORK_ID = 80
        const val INCOMING_ID = 81
        const val ACTION_STOP = "com.mokasas.wikall.STOP"
        const val ACTION_REJECT = "com.mokasas.wikall.REJECT"
    }
}

@AndroidEntryPoint
class CallSessionService : Service() {
    @Inject internal lateinit var controller: ConnectionController
    private var active: UUID? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel("wi-call-audio", "الصوت أثناء المكالمة", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, "wi-call-audio")
            .setSmallIcon(R.drawable.ic_wikall)
            .setContentTitle("مكالمة Wi-Call")
            .setContentText("الصوت أثناء المكالمة")
            .setContentIntent(openApp(this))
            .setOngoing(true)
            .build()
        val id = intent?.getStringExtra(CALL_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(82, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(82, notification)
        } catch (_: SecurityException) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (id != null && controller.state.value.callId == id) {
            active = id
            controller.startMedia(id)
        } else stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        active?.let(controller::audioServiceEnded)
        super.onDestroy()
    }
}

internal const val CALL_ID = "com.mokasas.wikall.CALL_ID"

private fun openApp(context: Context, incoming: Boolean = false): PendingIntent = PendingIntent.getActivity(
    context, if (incoming) 21 else 20,
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)
