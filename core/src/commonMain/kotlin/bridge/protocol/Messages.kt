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
    /** Certificate fingerprint. */
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
