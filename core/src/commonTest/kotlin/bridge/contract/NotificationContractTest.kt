@file:OptIn(ExperimentalCoroutinesApi::class)

package bridge.contract

import bridge.port.Connection
import bridge.port.ConnectionListener
import bridge.protocol.Codec
import bridge.protocol.Envelope
import bridge.protocol.Hello
import bridge.protocol.MAX_ICON_BYTES
import bridge.protocol.NotifPosted
import bridge.protocol.PROTOCOL_VERSION
import bridge.protocol.Platform
import bridge.protocol.ProtocolError
import bridge.session.CONTROL_PORT
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationContractTest {
    private val whatsapp = NotifPosted("0|com.whatsapp|1|chat|10123", "WhatsApp", "Asha", "Lunch?", listOf("Mark as read"), replyable = true)
    private val icon = ByteArray(1500) { it.toByte() }

    @Test
    fun tabletNotificationArrivesOnTheMacWithItsIcon() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.tab.notifications.events.posted(whatsapp, icon)
        runCurrent()
        val (shown, png) = p.mac.notifications.shown.single()
        assertEquals(whatsapp, shown)
        assertContentEquals(icon, png)
    }

    @Test
    fun anOversizedIconIsLeftOffButTheNotificationStillGoes() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.tab.notifications.events.posted(whatsapp, ByteArray(MAX_ICON_BYTES + 1))
        runCurrent()
        assertNull(p.mac.notifications.shown.single().second)
    }

    @Test
    fun replyActionAndDismissFromTheMacReachTheTablet() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.mac.notifications.events.reply(whatsapp.key, "On my way")
        p.mac.notifications.events.action(whatsapp.key, 0)
        p.mac.notifications.events.dismissed(whatsapp.key)
        runCurrent()
        assertEquals(listOf(whatsapp.key to "On my way"), p.tab.notifications.replies)
        assertEquals(listOf(whatsapp.key to 0), p.tab.notifications.actions)
        assertEquals(listOf(whatsapp.key), p.tab.notifications.removed)
    }

    @Test
    fun dismissOnTheTabletRemovesItFromTheMac() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.tab.notifications.events.dismissed(whatsapp.key)
        runCurrent()
        assertEquals(listOf(whatsapp.key), p.mac.notifications.removed)
    }

    @Test
    fun anUnpairedPeerCantPostNotifications() = runTest {
        val p = twoDevices()
        p.mac.bridge.openPairing(p.mac.host) // so the Mac keeps the unpaired connection open
        runCurrent()
        val fp = "ab".repeat(32)
        val (raw, replies) = rawPeer(p, fp)
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RA", 1, Hello(fp, "Intruder", Platform.ANDROID, "x", PROTOCOL_VERSION, emptyList()))))
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RB", 2, whatsapp, binaryLength = 3, mime = "image/png")))
        raw.sendBinary(byteArrayOf(1, 2, 3))
        runCurrent()
        assertEquals(emptyList(), p.mac.notifications.shown)
        assertEquals("unauthorized", replies.errors().single().code)
    }

    @Test
    fun aMissingBinaryFrameIsAnErrorAndTheNextMessageStillWorks() = runTest {
        val p = twoDevices()
        pairUp(p)
        // Swap the tablet's real session for a raw one presenting its identity, which the Mac trusts.
        val (raw, replies) = rawPeer(p, p.tab.id)
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RA", 1, Hello(p.tab.id, "Pad 3", Platform.ANDROID, "x", PROTOCOL_VERSION, emptyList()))))
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RB", 2, whatsapp, binaryLength = 3, mime = "image/png")))
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RC", 3, whatsapp.copy(key = "next"))))
        runCurrent()
        assertEquals(listOf("next"), p.mac.notifications.shown.map { it.first.key })
        assertEquals("invalid_message", replies.errors().single().code)
    }
}

/** A connection to the Mac presenting [fingerprint], driven by hand; returns it and the text frames it receives. */
internal fun rawPeer(p: Devices, fingerprint: String): Pair<Connection, MutableList<String>> {
    val raw = p.lan.dial(p.mac.host, CONTROL_PORT, fingerprint)!!
    val replies = mutableListOf<String>()
    raw.start(object : ConnectionListener {
        override fun onText(text: String) {
            replies += text
        }

        override fun onClosed(reason: String) {}
    })
    return raw to replies
}

internal fun List<String>.errors() = mapNotNull { Codec.decode(it).getOrThrow().message as? ProtocolError }
