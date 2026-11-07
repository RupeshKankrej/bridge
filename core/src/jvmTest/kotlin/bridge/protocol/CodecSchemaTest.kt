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
        Envelope(
            id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RN",
            ts = 1_762_440_021_000,
            message = NotifPosted("0|com.whatsapp|1|chat|10123", "WhatsApp", "Asha", "Lunch? 🍜", listOf("Mark as read", "Mute"), replyable = true),
            binaryLength = 1834,
            mime = PNG_MIME,
        ),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RP", ts = 1_762_440_021_100, message = NotifPosted("k", "Clock", "Alarm", "")),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RQ", ts = 1_762_440_022_000, message = NotifDismissed("0|com.whatsapp|1|chat|10123")),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RR", ts = 1_762_440_023_000, message = NotifAction("0|com.whatsapp|1|chat|10123", 1)),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RS", ts = 1_762_440_024_000, message = NotifReply("0|com.whatsapp|1|chat|10123", "On my way")),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RT", ts = 1_762_440_025_000, message = InputPointer(dx = 12.5, dy = -3.25, buttons = BUTTON_LEFT)),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RV", ts = 1_762_440_025_008, message = InputPointer(scrollX = -1.5, scrollY = 40.0)),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RW", ts = 1_762_440_025_016, message = InputPointer(gesture = InputGesture.ZOOM_OUT)),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RX", ts = 1_762_440_026_000, message = InputKey(keyCode = MacKey.forChar('c')!!.first, modifiers = MOD_COMMAND or MOD_SHIFT)),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RY", ts = 1_762_440_026_100, message = InputKey(text = "naïve 🙂")),
        Envelope(
            id = "01JBV0R0Z7Q4X8K3M5N6P7Q8RZ",
            ts = 1_762_440_027_000,
            message = MediaState("Spotify", "Dreams", "Fleetwood Mac", playing = true, position = 61.5, duration = 257.0, volume = 70),
        ),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8S0", ts = 1_762_440_027_100, message = MediaState()),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8S1", ts = 1_762_440_028_000, message = MediaCommand(MediaAction.WATCH)),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8S2", ts = 1_762_440_028_100, message = MediaCommand(MediaAction.VOLUME, 35)),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8S3", ts = 1_762_440_029_000, message = BatteryState(82, charging = true)),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8S4", ts = 1_762_440_030_000, message = ShareText(url = "https://example.com/a?b=c#d")),
        Envelope(id = "01JBV0R0Z7Q4X8K3M5N6P7Q8S5", ts = 1_762_440_030_100, message = ShareText(text = "Meet at 7\nTable for 2 ✓")),
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
