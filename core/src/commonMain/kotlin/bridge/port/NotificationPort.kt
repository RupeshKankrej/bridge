package bridge.port

import bridge.protocol.NotifPosted

/**
 * Notification mirroring (spec §5.4). The tablet's port reports posted/removed notifications and acts on them;
 * the Mac's port shows them and reports what the user did. Each platform overrides only its half.
 * Implementations must never log titles, text or replies.
 */
interface NotificationPort {
    /** Starts reporting local events to [events]; called once from core. */
    fun watch(events: NotificationEvents)

    /** Mac: show [notification], replacing one with the same key. [icon] is a PNG or null. */
    fun show(notification: NotifPosted, icon: ByteArray?) {}

    /** Both: the peer dismissed [key]; remove it here if still shown. */
    fun remove(key: String)

    /** Tablet: fire action [index] of the notification's non-reply actions. */
    fun action(key: String, index: Int) {}

    /** Tablet: send [text] through the notification's RemoteInput. */
    fun reply(key: String, text: String) {}
}

/** Implemented by core; the platform calls it from any thread. */
interface NotificationEvents {
    /** Tablet: a notification was posted or updated. [icon] is a PNG of at most [bridge.protocol.MAX_ICON_BYTES], or null. */
    fun posted(notification: NotifPosted, icon: ByteArray?)

    /** Either side: the user dismissed [key] here. */
    fun dismissed(key: String)

    /** Mac: the user clicked action [index] of [key]. */
    fun action(key: String, index: Int)

    /** Mac: the user typed [text] into the reply field of [key]. */
    fun reply(key: String, text: String)
}
