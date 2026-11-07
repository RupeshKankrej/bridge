package bridge.protocol

import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val FP_MAC = "3f9a0c5b1e7d2a4c6b8e0f1a3c5e7092b4d6f8a0c2e4f6a8b0d2e4f6a8c0e2f4"
private const val FP_TAB = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"

class CodecSchemaTest {
    private val protocolDir = File(System.getProperty("bridge.protocolDir") ?: error("run via Gradle: bridge.protocolDir not set"))

    private fun schema(name: String): JsonObject = Json.parseToJsonElement(File(protocolDir, "$name.json").readText()).jsonObject

    private val samples: List<Envelope> = listOf(
        Envelope(
            id = "01JBV0R0Z7Q4X8K3M5N6P7Q8R9",
            ts = 1_762_440_000_000,
            message = Hello(
                deviceId = FP_MAC,
                name = "Rupesh's MacBook Air",
                platform = Platform.MACOS,
                appVersion = "0.1.0",
                protocolVersion = PROTOCOL_VERSION,
                capabilities = listOf("clipboard", "files"),
            ),
        ),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RA", ts = 1_762_440_015_000, message = Ping),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RB", ts = 1_762_440_015_020, message = Pong, replyTo = "01JBV0R0Z7Q4X8K3M5N6P7Q8RA"),
        Envelope(
            id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RC",
            ts = 1_762_440_016_000,
            message = ProtocolError(code = "unsupported_type", message = "unknown type 'foo.bar'"),
            replyTo = "01JBV0R0Z7Q4X8K3M5N6P7Q8RB",
        ),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RF", ts = 1_762_440_017_000, message = PairRequest(FP_TAB, "042917")),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RG", ts = 1_762_440_018_000, message = PairConfirm(FP_TAB, "042917")),
        Envelope(
            id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RH",
            ts = 1_762_440_018_100,
            message = PairReject(FP_MAC, "042917"),
            replyTo = "01JBV0R0Z7Q4X8K3M5N6P7Q8RF",
        ),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RJ", ts = 1_762_440_019_000, message = ClipboardSet(TEXT_MIME, "héllo \"wörld\"\n✓")),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RK", ts = 1_762_440_019_100, message = ClipboardSet(TEXT_MIME, "x", sourceApp = "com.apple.Safari")),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RM", ts = 1_762_440_020_000, message = ClipboardRequest),
    )

    @Test
    fun everyMessageTypeHasASampleAndASchema() {
        val wireTypes = Message.serializer().descriptor.getElementDescriptor(1).elementNames.toSet()
        val sampled = samples.map { Json.parseToJsonElement(Codec.encode(it)).jsonObject.getValue("type").jsonPrimitive.content }.toSet()
        assertEquals(wireTypes, sampled, "add a sample to CodecSchemaTest for each new message type")
        wireTypes.forEach { assertTrue(File(protocolDir, "$it.json").isFile, "missing tools/protocol/$it.json") }
    }

    @Test
    fun roundTripsAndMatchesSchemas() {
        val envelopeSchema = schema("envelope")
        for (sample in samples) {
            val text = Codec.encode(sample)
            val wire = Json.parseToJsonElement(text).jsonObject
            val type = wire.getValue("type").jsonPrimitive.content

            assertEquals(emptyList(), SchemaValidator.validate(envelopeSchema, wire), "envelope of $type")
            assertEquals(emptyList(), SchemaValidator.validate(schema(type), wire.getValue("payload")), "payload of $type")
            assertEquals(sample, Codec.decode(text).getOrThrow(), "round trip of $type")
        }
    }

    @Test
    fun decodeRejectsUnknownType() {
        val text = """{"v":1,"id":"01JBV0R0Z7Q4X8K3M5N6P7Q8RD","type":"foo.bar","ts":1,"payload":{}}"""
        val e = assertFailsWith<UnsupportedTypeException> { Codec.decode(text).getOrThrow() }
        assertEquals("foo.bar" to "01JBV0R0Z7Q4X8K3M5N6P7Q8RD", e.type to e.id)
    }

    @Test
    fun decodeIgnoresUnknownPayloadFields() {
        val text = """{"v":1,"id":"01JBV0R0Z7Q4X8K3M5N6P7Q8RE","type":"ping","ts":1,"payload":{"future":true}}"""
        assertEquals(Ping, Codec.decode(text).getOrThrow().message)
    }

    @Test
    fun validatorCatchesViolationsAndRejectsUnknownKeywords() {
        val bad = buildJsonObject { put("v", 2); put("id", "nope"); put("type", "ping"); put("ts", 1); put("extra", 1) }
        val errors = SchemaValidator.validate(schema("envelope"), bad)
        listOf("\$.v", "\$.id", "missing required 'payload'", "unexpected property 'extra'").forEach { needle ->
            assertTrue(errors.any { needle in it }, "expected a violation mentioning $needle in $errors")
        }
        assertFailsWith<IllegalArgumentException> {
            SchemaValidator.validate(buildJsonObject { put("oneOf", "x") }, bad)
        }
    }
}
