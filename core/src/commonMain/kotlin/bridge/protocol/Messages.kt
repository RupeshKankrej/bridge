package bridge.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val PROTOCOL_VERSION = 1

/**
 * A control-channel payload. The [SerialName] of each subclass is its wire `type`;
 * adding a subclass is all [Codec] needs to know about it (plus a schema in tools/protocol/).
 */
@Serializable
sealed interface Message

@Serializable
@SerialName("hello")
data class Hello(
    /** SHA-256 of the DER TLS certificate, lowercase hex: the device ID. */
    val deviceId: String,
    val name: String,
    val platform: Platform,
    val appVersion: String,
    val protocolVersion: Int,
    val capabilities: List<String>,
) : Message

@Serializable
enum class Platform {
    @SerialName("macos") MACOS,
    @SerialName("android") ANDROID,
    @SerialName("ios") IOS,
}

@Serializable
@SerialName("ping")
data object Ping : Message

@Serializable
@SerialName("pong")
data object Pong : Message

/** Wire type `error`. Not named `Error` to avoid shadowing kotlin.Error. The request it answers is the envelope's `replyTo`. */
@Serializable
@SerialName("error")
data class ProtocolError(
    /** Stable snake_case identifier, e.g. `unsupported_type`. */
    val code: String,
    val message: String,
) : Message

/**
 * Pairing (spec §5.1). All three carry the sender's own certificate fingerprint and the 6-digit
 * code from the QR invite; `pair.reject` echoes the code it is rejecting.
 */
@Serializable
@SerialName("pair.request")
data class PairRequest(val fingerprint: String, val confirmCode: String) : Message

@Serializable
@SerialName("pair.confirm")
data class PairConfirm(val fingerprint: String, val confirmCode: String) : Message

@Serializable
@SerialName("pair.reject")
data class PairReject(val fingerprint: String, val confirmCode: String) : Message

const val TEXT_MIME = "text/plain"

/** UTF-8 cap for clipboard text (spec §5.2); larger content goes as a file. */
const val MAX_CLIPBOARD_BYTES = 1_048_576

/**
 * Clipboard content (spec §5.2). The envelope's `ts` is when the content was copied, not when this
 * message was sent, so a resend after `clipboard.request` still loses to anything copied later.
 * Phase 1 sends `text/plain` only; [text] is optional because images (Phase 2) come as a binary frame.
 */
@Serializable
@SerialName("clipboard.set")
data class ClipboardSet(val mime: String, val text: String? = null, val sourceApp: String? = null) : Message

/** Asks the peer to resend its current clipboard as a `clipboard.set` with `replyTo` set. */
@Serializable
@SerialName("clipboard.request")
data object ClipboardRequest : Message

/** Icon cap for `notif.posted` (spec §5.4): a 32 px PNG is a few KB; anything far larger is refused. */
const val MAX_ICON_BYTES = 65_536

const val PNG_MIME = "image/png"

/**
 * A notification posted on the tablet (spec §5.4). [key] is Android's notification key; a repost with the same
 * key replaces the shown one. [actions] are the titles of the actions without a RemoteInput, in order; `notif.action`
 * indexes into it. [replyable]: one action takes a RemoteInput, reached with `notif.reply`. The app icon, if any,
 * follows as a binary frame (envelope `binaryLength`, `mime` image/png).
 */
@Serializable
@SerialName("notif.posted")
data class NotifPosted(
    val key: String,
    val app: String,
    val title: String,
    val text: String,
    val actions: List<String> = emptyList(),
    val replyable: Boolean = false,
) : Message

/** Either side: the notification [key] was dismissed there. */
@Serializable
@SerialName("notif.dismissed")
data class NotifDismissed(val key: String) : Message

/** Mac → tablet: run action [actionIndex] of `notif.posted.actions`. */
@Serializable
@SerialName("notif.action")
data class NotifAction(val key: String, val actionIndex: Int) : Message

/** Mac → tablet: send [replyText] through the notification's RemoteInput. */
@Serializable
@SerialName("notif.reply")
data class NotifReply(val key: String, val replyText: String) : Message

/** `input.pointer.buttons` bits: the buttons held down after this event. */
const val BUTTON_LEFT = 1
const val BUTTON_RIGHT = 2

/** `input.key.modifiers` bits. */
const val MOD_SHIFT = 1
const val MOD_CONTROL = 2
const val MOD_OPTION = 4
const val MOD_COMMAND = 8

/** One zoom step; the Mac maps it to ⌘= / ⌘− (ADR 0010). */
@Serializable
enum class InputGesture {
    @SerialName("zoomIn") ZOOM_IN,
    @SerialName("zoomOut") ZOOM_OUT,
}

/**
 * Tablet → Mac pointer event (spec §5.5). [dx]/[dy] move the pointer in Mac points; [scrollX]/[scrollY] scroll by that
 * many pixels in the direction the fingers moved (positive = content moves right/down). Deltas are sums of everything
 * since the previous message (coalescing). [buttons] is the held-button state: the Mac moves first, then presses or
 * releases whatever differs from the previous message, so a click is `buttons: 1` followed by `buttons: 0`.
 */
@Serializable
@SerialName("input.pointer")
data class InputPointer(
    val dx: Double = 0.0,
    val dy: Double = 0.0,
    val buttons: Int = 0,
    val scrollX: Double = 0.0,
    val scrollY: Double = 0.0,
    val gesture: InputGesture? = null,
) : Message

/**
 * Tablet → Mac key event (spec §5.5): exactly one of [keyCode] (a [MacKey] code, pressed and released with [modifiers])
 * or [text] (typed as-is, for IME text without ⌘/⌃/⌥).
 */
@Serializable
@SerialName("input.key")
data class InputKey(val keyCode: Int? = null, val modifiers: Int = 0, val text: String? = null) : Message

/**
 * Mac → tablet: what the Mac is playing (spec §5.6). All fields null = nothing playing. [position] and [duration] are
 * seconds; the Mac sends on every change but not for the position ticking along, so the receiver advances [position]
 * itself while [playing]. [volume] is the player's own volume, 0–100.
 */
@Serializable
@SerialName("media.state")
data class MediaState(
    val app: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val playing: Boolean = false,
    val position: Double? = null,
    val duration: Double? = null,
    val volume: Int? = null,
) : Message

@Serializable
enum class MediaAction {
    @SerialName("play") PLAY,
    @SerialName("pause") PAUSE,
    @SerialName("next") NEXT,
    @SerialName("prev") PREV,

    /** Set the player's volume to `media.command.volume`. */
    @SerialName("volume") VOLUME,

    /** Start / stop sending `media.state` to this peer; the Mac only polls players while someone watches (ADR 0011). */
    @SerialName("watch") WATCH,
    @SerialName("unwatch") UNWATCH,
}

/** Tablet → Mac: control the Mac's player. [volume] (0–100) only with [MediaAction.VOLUME]. */
@Serializable
@SerialName("media.command")
data class MediaCommand(val action: MediaAction, val volume: Int? = null) : Message

/** Tablet → Mac: battery [level] in percent and whether a charger is connected (spec §5.6). */
@Serializable
@SerialName("battery.state")
data class BatteryState(val level: Int, val charging: Boolean) : Message

/**
 * Either way: open a shared link or text on the peer (F12). Exactly one of [url] (http or https; opened in the
 * browser) or [text] (at most [MAX_CLIPBOARD_BYTES]). Never log either.
 */
@Serializable
@SerialName("share.text")
data class ShareText(val text: String? = null, val url: String? = null) : Message {
    companion object {
        /** A shared string that is just one http(s) link becomes [url]; anything else is [text]. */
        fun of(shared: String): ShareText {
            val s = shared.trim()
            return if (isWebUrl(s)) ShareText(url = s) else ShareText(text = shared)
        }

        fun isWebUrl(s: String) = s.none { it.isWhitespace() } && (s.startsWith("https://", true) || s.startsWith("http://", true)) && s.length > 8
    }
}
