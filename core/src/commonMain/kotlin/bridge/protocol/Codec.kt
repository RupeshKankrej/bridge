package bridge.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** A well-formed envelope whose `type` this build doesn't know, e.g. from a newer peer. [id] is the envelope's. */
class UnsupportedTypeException(val type: String, val id: String) : IllegalArgumentException("unsupported type '$type'")

/** One control-channel message: the spec §4 envelope with its typed payload. */
data class Envelope(
    /** ULID. */
    val id: String,
    /** Sender epoch millis. */
    val ts: Long,
    val message: Message,
    val replyTo: String? = null,
    val binaryLength: Long? = null,
    val mime: String? = null,
    val v: Int = PROTOCOL_VERSION,
)

@Serializable
private class WireEnvelope(
    val v: Int,
    val id: String,
    val type: String,
    val ts: Long,
    val replyTo: String? = null,
    val binaryLength: Long? = null,
    val mime: String? = null,
    val payload: JsonObject,
)

/**
 * Maps [Envelope] to and from the JSON text frame. The payload is encoded through the sealed
 * [Message] serializer, whose class discriminator is the wire type; the codec lifts it out of the
 * payload into the envelope's `type` field and puts it back when decoding.
 */
object Codec {
    // "@type" can't be a Kotlin property name, so it never collides with a payload field.
    private const val DISCRIMINATOR = "@type"

    // Element 1 of a sealed class descriptor lists the subclasses by serial name, i.e. the wire types.
    private val knownTypes = Message.serializer().descriptor.getElementDescriptor(1).elementNames.toSet()

    private val json = Json {
        classDiscriminator = DISCRIMINATOR
        // Tolerant reader: a peer on the same major version may add optional fields.
        ignoreUnknownKeys = true
    }

    fun encode(envelope: Envelope): String {
        val tagged = json.encodeToJsonElement(Message.serializer(), envelope.message).jsonObject
        val wire = WireEnvelope(
            v = envelope.v,
            id = envelope.id,
            type = (tagged.getValue(DISCRIMINATOR) as JsonPrimitive).content,
            ts = envelope.ts,
            replyTo = envelope.replyTo,
            binaryLength = envelope.binaryLength,
            mime = envelope.mime,
            payload = JsonObject(tagged - DISCRIMINATOR),
        )
        return json.encodeToString(WireEnvelope.serializer(), wire)
    }

    /** Fails with [UnsupportedTypeException] for an unknown type, SerializationException / IllegalArgumentException if malformed. */
    fun decode(text: String): Result<Envelope> = runCatching {
        val wire = json.decodeFromString(WireEnvelope.serializer(), text)
        if (wire.type !in knownTypes) throw UnsupportedTypeException(wire.type, wire.id)
        val tagged = JsonObject(wire.payload + (DISCRIMINATOR to JsonPrimitive(wire.type)))
        Envelope(
            id = wire.id,
            ts = wire.ts,
            message = json.decodeFromJsonElement(Message.serializer(), tagged),
            replyTo = wire.replyTo,
            binaryLength = wire.binaryLength,
            mime = wire.mime,
            v = wire.v,
        )
    }
}
