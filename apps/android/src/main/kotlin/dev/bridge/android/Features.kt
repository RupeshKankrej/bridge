package dev.bridge.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import bridge.port.SharePort
import bridge.protocol.ShareText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The tablet's on/off switches for media controls, battery reporting and link sharing (spec §7: off until turned on). */
class Features(context: Context) {
    private val prefs = context.getSharedPreferences("features", Context.MODE_PRIVATE)

    inner class Toggle(private val key: String) {
        private val _on = MutableStateFlow(prefs.getBoolean(key, false))
        val on: StateFlow<Boolean> = _on.asStateFlow()

        fun set(value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
            _on.value = value
        }
    }

    val media = Toggle("media")
    val battery = Toggle("battery")
    val share = Toggle("share")

    /**
     * AirDrop visibility (spec §5.10). Plumbing only until Tier A or B exists: nothing advertises yet, the UI says so.
     * Everyone reverts to Bridge devices only at [everyoneUntil] unless that is 0 ("always", for home use).
     */
    enum class AirDrop { OFF, BRIDGE, EVERYONE }

    private val _airDrop = MutableStateFlow(readAirDrop())
    val airDrop: StateFlow<AirDrop> = _airDrop.asStateFlow()
    private val _everyoneUntil = MutableStateFlow(prefs.getLong("airdropUntil", 0))
    val everyoneUntil: StateFlow<Long> = _everyoneUntil.asStateFlow()
    private val revert = Handler(Looper.getMainLooper())

    fun setAirDrop(value: AirDrop, always: Boolean = false) {
        val until = if (value == AirDrop.EVERYONE && !always) System.currentTimeMillis() + EVERYONE_FOR else 0
        prefs.edit().putString("airdrop", value.name).putLong("airdropUntil", until).apply()
        _airDrop.value = value
        _everyoneUntil.value = until
        scheduleRevert()
    }

    private fun readAirDrop(): AirDrop {
        val stored = prefs.getString("airdrop", null)?.let(AirDrop::valueOf) ?: AirDrop.BRIDGE
        val until = prefs.getLong("airdropUntil", 0)
        return if (stored == AirDrop.EVERYONE && until != 0L && until <= System.currentTimeMillis()) AirDrop.BRIDGE else stored
    }

    private fun scheduleRevert() {
        revert.removeCallbacksAndMessages(null)
        val until = _everyoneUntil.value
        if (_airDrop.value == AirDrop.EVERYONE && until != 0L) {
            revert.postDelayed({ setAirDrop(AirDrop.BRIDGE) }, (until - System.currentTimeMillis()).coerceAtLeast(0))
        }
    }

    init {
        scheduleRevert()
    }

    /** SAF tree chosen as the receive folder; null = Download/Bridge via MediaStore (spec §7). */
    private val _receiveFolder = MutableStateFlow(prefs.getString("receiveFolder", null)?.let(Uri::parse))
    val receiveFolder: StateFlow<Uri?> = _receiveFolder.asStateFlow()

    fun setReceiveFolder(tree: Uri?) {
        prefs.edit().putString("receiveFolder", tree?.toString()).apply()
        _receiveFolder.value = tree
    }

    private companion object {
        const val EVERYONE_FOR = 10 * 60 * 1000L
    }
}

/**
 * SharePort: a link or text from the Mac becomes a notification, because Android 10+ doesn't let a background app
 * open an activity. Tapping a link opens the browser; tapping text opens the share sheet (which has Copy).
 */
class AndroidShare(private val context: Context, private val enabled: StateFlow<Boolean>) : SharePort {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var nextId = 100

    override fun open(share: ShareText): Boolean {
        if (!enabled.value) return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Links and text from the Mac", NotificationManager.IMPORTANCE_HIGH))
        val url = share.url
        val (title, body, intent) = if (url != null) {
            Triple("Link from your Mac", url, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } else {
            val text = share.text.orEmpty()
            Triple("Text from your Mac", text, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
        }
        val id = nextId++
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(PendingIntent.getActivity(context, id, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
        return true
    }

    private companion object {
        const val CHANNEL = "shared"
    }
}
