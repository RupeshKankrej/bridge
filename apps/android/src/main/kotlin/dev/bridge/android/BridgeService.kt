package dev.bridge.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo

/**
 * Foreground service (type connectedDevice) whose only job is to keep the process, and with it
 * the core session and heartbeat, alive while the app is in the background.
 */
class BridgeService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // Background apps can't read the clipboard; this opens ClipboardActivity, which can.
        val sendClipboard = PendingIntent.getActivity(this, 1, Intent(this, ClipboardActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Bridge is running")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Send clipboard", sendClipboard).build())
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        (application as BridgeApplication).bridge.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?) = null

    private companion object {
        const val CHANNEL = "connection"
    }
}
