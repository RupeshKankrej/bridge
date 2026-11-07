package dev.bridge.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
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
