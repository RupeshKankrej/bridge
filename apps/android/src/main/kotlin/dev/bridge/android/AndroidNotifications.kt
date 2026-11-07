package dev.bridge.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import bridge.port.NotificationEvents
import bridge.port.NotificationPort
import bridge.protocol.NotifPosted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/**
 * NotificationPort on [MirrorService] (spec §5.4). Forwards notifications while mirroring is on, notification access
 * is granted and the app isn't blocked; runs the Mac's actions, replies and dismissals. All calls arrive on the main
 * thread (listener callbacks and core's Dispatchers.Main), so the maps need no locks. Never logs notification content.
 */
class AndroidNotifications(private val context: Context) : NotificationPort {
    private val prefs = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)
    private var events: NotificationEvents? = null

    /** Set while the system has the listener bound. */
    internal var service: NotificationListenerService? = null

    /** Spec §7: off until the user turns it on. */
    private val _enabled = MutableStateFlow(prefs.getBoolean(ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _blocked = MutableStateFlow(prefs.getStringSet(BLOCKED, emptySet())!!.toSet())

    /** Packages whose notifications stay on the tablet. */
    val blocked: StateFlow<Set<String>> = _blocked.asStateFlow()

    /** What the Mac can act on, per forwarded key: the non-reply actions in `notif.posted` order, and the reply action. */
    private class Forwarded(val title: String, val text: String, val actions: List<Notification.Action>, val reply: Notification.Action?)

    private val forwarded = mutableMapOf<String, Forwarded>()
    private val lastReply = mutableMapOf<String, String>()
    private val icons = mutableMapOf<String, ByteArray?>()

    val hasAccess get() = context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(context, MirrorService::class.java))

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(ENABLED, on).apply()
        _enabled.value = on
    }

    fun setBlocked(pkg: String, block: Boolean) {
        val set = if (block) _blocked.value + pkg else _blocked.value - pkg
        prefs.edit().putStringSet(BLOCKED, set).apply()
        _blocked.value = set
    }

    override fun watch(events: NotificationEvents) {
        this.events = events
    }

    internal fun posted(sbn: StatusBarNotification) {
        val n = sbn.notification
        // Ongoing (music, the Bridge service itself) and group summaries (a second copy of a chat) aren't worth mirroring.
        if (!enabled.value || sbn.packageName == context.packageName || sbn.packageName in blocked.value) return
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val previous = forwarded[sbn.key]
        // Silent updates (progress, a re-sort) and the app echoing our own reply back into the notification.
        if (previous != null && previous.title == title && previous.text == text) return
        if (lastReply[sbn.key] == text) return
        val all = n.actions.orEmpty().toList()
        val reply = all.firstOrNull { a -> a.remoteInputs.orEmpty().any { it.allowFreeFormInput } }
        val actions = all.filter { it !== reply }
        forwarded[sbn.key] = Forwarded(title, text, actions, reply)
        val label = runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(sbn.packageName, 0)).toString() } }
            .getOrDefault(sbn.packageName)
        events?.posted(
            NotifPosted(sbn.key, label, title, text, actions.map { it.title?.toString().orEmpty() }, reply != null),
            icons.getOrPut(sbn.packageName) { appIcon(sbn.packageName) },
        )
    }

    internal fun removed(sbn: StatusBarNotification, reason: Int) {
        lastReply.remove(sbn.key)
        if (forwarded.remove(sbn.key) == null) return
        // We cancelled it because the Mac dismissed it; don't echo.
        if (reason != NotificationListenerService.REASON_LISTENER_CANCEL) events?.dismissed(sbn.key)
    }

    override fun remove(key: String) {
        service?.cancelNotification(key)
    }

    override fun action(key: String, index: Int) {
        val action = forwarded[key]?.actions?.getOrNull(index) ?: run { Log.d(TAG, "action on gone notification"); return }
        fire(action.actionIntent, null)
    }

    /** The standard inline-reply handshake: put the text under each RemoteInput's result key and fire the action's intent. */
    override fun reply(key: String, text: String) {
        val action = forwarded[key]?.reply ?: run { Log.d(TAG, "reply to gone notification"); return }
        val inputs = action.remoteInputs
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } })
        lastReply[key] = text
        fire(action.actionIntent, intent)
    }

    private fun fire(pending: PendingIntent, fillIn: Intent?) {
        try {
            pending.send(context, 0, fillIn)
        } catch (e: PendingIntent.CanceledException) {
            Log.d(TAG, "notification action no longer valid: $e")
        }
    }

    /** The app's launcher icon as a 32 px PNG; the Mac shows it as the notification's thumbnail. */
    private fun appIcon(pkg: String): ByteArray? = runCatching {
        val drawable = context.packageManager.getApplicationIcon(pkg)
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, ICON_PX, ICON_PX)
        drawable.draw(Canvas(bitmap))
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }.getOrNull()

    private companion object {
        const val TAG = "BridgeNotif"
        const val ENABLED = "enabled"
        const val BLOCKED = "blocked"
        const val ICON_PX = 32
    }
}

/** Bound by the system once the user grants notification access; hands everything to [AndroidNotifications]. */
class MirrorService : NotificationListenerService() {
    private val port get() = (application as BridgeApplication).notifications

    override fun onListenerConnected() {
        port.service = this
    }

    override fun onListenerDisconnected() {
        port.service = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = port.posted(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) = port.removed(sbn, reason)
}
