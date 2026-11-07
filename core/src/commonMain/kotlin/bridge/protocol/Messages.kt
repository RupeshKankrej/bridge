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
