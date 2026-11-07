@file:OptIn(ExperimentalCoroutinesApi::class)

package bridge.contract

import bridge.protocol.BUTTON_LEFT
import bridge.protocol.BUTTON_RIGHT
import bridge.protocol.Codec
import bridge.protocol.Envelope
import bridge.protocol.Hello
import bridge.protocol.InputGesture
import bridge.protocol.InputKey
import bridge.protocol.InputPointer
import bridge.protocol.MOD_COMMAND
import bridge.protocol.MacKey
import bridge.protocol.PROTOCOL_VERSION
import bridge.protocol.Platform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InputContractTest {
    @Test
    fun movesWithinOneSlotArriveAsOneMessageWithTheirSum() = runTest {
        val p = twoDevices()
        pairUp(p)
        repeat(100) { p.tab.bridge.pointer(1.0, -0.5) }
        runCurrent()
        assertEquals(listOf<Any>(InputPointer(dx = 100.0, dy = -50.0)), p.mac.input.received)
    }

    @Test
    fun aSecondOfFastMovesIsCappedAt120MessagesAndLosesNoDistance() = runTest {
        val p = twoDevices()
        pairUp(p)
        // A 1000 Hz touch stream for one second.
        repeat(1000) {
            p.tab.bridge.pointer(1.0, 2.0, scrollY = 0.25)
            advanceTimeBy(1)
        }
        advanceTimeBy(100)
        val got = p.mac.input.received.map { it as InputPointer }
        assertTrue(got.size in 100..121, "${got.size} messages")
        assertEquals(1000.0, got.sumOf { it.dx })
        assertEquals(2000.0, got.sumOf { it.dy })
        assertEquals(250.0, got.sumOf { it.scrollY })
    }

    @Test
    fun clicksKeysAndGesturesKeepTheirOrderAndAreNeverMerged() = runTest {
        val p = twoDevices()
        pairUp(p)
        with(p.tab.bridge) {
            pointer(5.0, 0.0)
            buttons(BUTTON_LEFT)
            pointer(3.0, 0.0)
            buttons(0)
            buttons(BUTTON_RIGHT)
            buttons(0)
            zoom(InputGesture.ZOOM_IN)
            key(MacKey.forChar('c')!!.first, MOD_COMMAND)
            type("héllo")
        }
        advanceTimeBy(1000)
        assertEquals(
            listOf<Any>(
                InputPointer(dx = 5.0),
                InputPointer(buttons = BUTTON_LEFT),
                InputPointer(dx = 3.0, buttons = BUTTON_LEFT),
                InputPointer(buttons = 0),
                InputPointer(buttons = BUTTON_RIGHT),
                InputPointer(buttons = 0),
                InputPointer(gesture = InputGesture.ZOOM_IN),
                InputKey(keyCode = 8, modifiers = MOD_COMMAND),
                InputKey(text = "héllo"),
            ),
            p.mac.input.received,
        )
    }

    @Test
    fun withoutAccessibilityTheTabletIsToldOnceAndAgainAfterItRecovers() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.mac.input.allowed = false
        repeat(5) {
            p.tab.bridge.pointer(1.0, 0.0)
            advanceTimeBy(10)
        }
        assertTrue("Accessibility" in p.tab.state.message!!)
        assertEquals(1, p.lan.sent.count { "permission_missing" in it })

        p.mac.input.allowed = true
        p.tab.bridge.pointer(1.0, 0.0)
        advanceTimeBy(10)
        p.mac.input.allowed = false
        p.tab.bridge.pointer(1.0, 0.0)
        advanceTimeBy(10)
        assertEquals(2, p.lan.sent.count { "permission_missing" in it })
    }

    @Test
    fun anUnpairedPeerCantInject() = runTest {
        val p = twoDevices()
        p.mac.bridge.openPairing(p.mac.host)
        runCurrent()
        val fp = "ab".repeat(32)
        val (raw, replies) = rawPeer(p, fp)
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RA", 1, Hello(fp, "Intruder", Platform.ANDROID, "x", PROTOCOL_VERSION, emptyList()))))
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RB", 2, InputKey(text = "rm -rf ~\n"))))
        runCurrent()
        assertEquals(emptyList(), p.mac.input.received)
        assertEquals("unauthorized", replies.errors().single().code)
    }

    @Test
    fun aKeyWithNeitherOrBothOfKeyCodeAndTextIsRejected() = runTest {
        val p = twoDevices()
        pairUp(p)
        val (raw, replies) = rawPeer(p, p.tab.id)
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RA", 1, Hello(p.tab.id, "Pad 3", Platform.ANDROID, "x", PROTOCOL_VERSION, emptyList()))))
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RB", 2, InputKey())))
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RC", 3, InputKey(keyCode = 1, text = "s"))))
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RD", 4, InputKey(keyCode = 999))))
        runCurrent()
        assertEquals(emptyList(), p.mac.input.received)
        assertEquals(listOf("invalid_message", "invalid_message", "invalid_message"), replies.errors().map { it.code })
    }
}
