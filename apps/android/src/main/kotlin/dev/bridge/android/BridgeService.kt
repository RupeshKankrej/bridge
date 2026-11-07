package dev.bridge.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.text.format.Formatter
import bridge.files.FilesState
import bridge.files.Offer
import bridge.files.TransferStatus
import bridge.protocol.BatteryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

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
    private lateinit var notification: Notification

    /** Unlocking is the moment the user expects the Mac to be there; don't wait out the reconnect backoff. */
    private val userPresent = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = (application as BridgeApplication).bridge.reconnect()
    }

    /** Spec §5.6: the battery goes out at once when a charger is plugged in or out, not at the next 5-minute tick. */
    private val charger = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = application as BridgeApplication
            if (app.features.battery.on.value) app.bridge.battery(battery(charging = intent.action == Intent.ACTION_POWER_CONNECTED))
        }
    }

    // Fires once on registration too; a reconnect then is harmless.
    private val networkChanged = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = (application as BridgeApplication).bridge.reconnect()
    }

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(OFFERS, "Incoming files", NotificationManager.IMPORTANCE_HIGH))
        val sendClipboard = PendingIntent.getActivity(this, 1, Intent(this, ClipboardActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        notification = Notification.Builder(this, CHANNEL)
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
        // Manifest receivers can't get USER_PRESENT since Android 8; only a running context can.
        registerReceiver(userPresent, IntentFilter(Intent.ACTION_USER_PRESENT))
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkChanged)
        ServiceWatchdog.schedule(this)
        scope.launch { app.files.state.collect(::onFiles) }
        // Manifest receivers can't get the charger broadcasts since Android 8 either.
        registerReceiver(charger, IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) })
        scope.launch {
            app.features.battery.on.collectLatest { on ->
                while (on) {
                    app.bridge.battery(battery())
                    delay(5.minutes)
                }
                app.bridge.battery(null)
            }
        }
    }

    /** [charging] from the charger broadcast wins over BatteryManager, which can lag it by a moment. */
    private fun battery(charging: Boolean? = null): BatteryState {
        val manager = getSystemService(BatteryManager::class.java)
        val plugged = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return BatteryState(manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), charging ?: (plugged != 0))
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() (e.g. from the watchdog) must be answered with startForeground() within seconds.
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        unregisterReceiver(userPresent)
        unregisterReceiver(charger)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkChanged)
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
