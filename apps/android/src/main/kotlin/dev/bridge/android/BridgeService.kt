package dev.bridge.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.text.format.Formatter
import bridge.files.FilesState
import bridge.files.Offer
import bridge.files.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (type connectedDevice) that keeps the process, and with it the core session,
 * heartbeat and file server, alive while the app is in the background.
 */
class BridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    // A foreground service keeps the process, not the CPU or Wi-Fi radio, awake with the screen off.
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bridge:transfer").apply { setReferenceCounted(false) }
    }

    @Suppress("DEPRECATION") // HIGH_PERF keeps Wi-Fi out of power save with the screen off; LOW_LATENCY only works on screen.
    private val wifiLock by lazy {
        getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "bridge:transfer").apply { setReferenceCounted(false) }
    }
    private var shownOffer: String? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(OFFERS, "Incoming files", NotificationManager.IMPORTANCE_HIGH))
        val sendClipboard = PendingIntent.getActivity(this, 1, Intent(this, ClipboardActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Bridge is running")
            .setContentIntent(openApp())
            // Background apps can't read the clipboard; this opens ClipboardActivity, which can.
            .addAction(Notification.Action.Builder(null, "Send clipboard", sendClipboard).build())
            .setOngoing(true)
            .build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val app = application as BridgeApplication
        app.bridge.start()
        app.files.start()
        scope.launch { app.files.state.collect(::onFiles) }
    }

    private fun onFiles(state: FilesState) {
        val active = state.transfers.any { it.status in ACTIVE }
        if (active) {
            wakeLock.acquire(60 * 60 * 1000L) // renewed on every progress update; the timeout is a safety net
            wifiLock.acquire()
        } else {
            if (wakeLock.isHeld) wakeLock.release()
            if (wifiLock.isHeld) wifiLock.release()
        }
        if (state.offer?.id != shownOffer) {
            notifications.cancel(OFFER_NOTIFICATION)
            shownOffer = state.offer?.id
            state.offer?.let(::notifyOffer)
        }
    }

    /** Heads-up notification with Accept / Decline, so an offer is seen with Bridge in the background. */
    private fun notifyOffer(offer: Offer) {
        fun answer(accept: Boolean) = PendingIntent.getBroadcast(
            this, if (accept) 10 else 11,
            Intent(this, OfferReceiver::class.java).putExtra(OfferReceiver.OFFER, offer.id).putExtra(OfferReceiver.ACCEPT, accept),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val total = Formatter.formatShortFileSize(this, offer.files.sumOf { it.size })
        val count = offer.files.size
        val notification = Notification.Builder(this, OFFERS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("${offer.senderName} wants to send $count file${if (count == 1) "" else "s"} ($total)")
            .setContentText(offer.files.take(3).joinToString { it.name } + if (count > 3) ", …" else "")
            .setContentIntent(openApp())
            .setCategory(Notification.CATEGORY_MESSAGE)
            .addAction(Notification.Action.Builder(null, "Decline", answer(false)).build())
            .addAction(Notification.Action.Builder(null, "Accept", answer(true)).build())
            .build()
        notifications.notify(OFFER_NOTIFICATION, notification)
    }

    private fun openApp() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock.isHeld) wifiLock.release()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "connection"
        const val OFFERS = "offers"
        const val OFFER_NOTIFICATION = 2
        val ACTIVE = setOf(TransferStatus.WAITING, TransferStatus.RUNNING, TransferStatus.PAUSED)
    }
}

/** Accept / Decline from the offer notification. */
class OfferReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val offer = intent.getStringExtra(OFFER) ?: return
        (context.applicationContext as BridgeApplication).files.respond(offer, intent.getBooleanExtra(ACCEPT, false))
    }

    companion object {
        const val OFFER = "offer"
        const val ACCEPT = "accept"
    }
}
