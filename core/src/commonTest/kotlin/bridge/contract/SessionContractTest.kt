@file:OptIn(ExperimentalCoroutinesApi::class)

package bridge.contract

import bridge.identity.fingerprint
import bridge.port.ConnectionListener
import bridge.protocol.Codec
import bridge.protocol.Envelope
import bridge.protocol.Hello
import bridge.protocol.PROTOCOL_VERSION
import bridge.protocol.Ping
import bridge.protocol.ProtocolError
import bridge.protocol.Platform
import bridge.session.CLOSE_POLICY
import bridge.session.CLOSE_VERSION_MISMATCH
import bridge.session.CONTROL_PORT
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SessionContractTest {
    private fun online(d: Device) = d.state.paired.singleOrNull()?.online == true

    @Test
    fun pairedSessionSurvivesTenMinutesIdleOnHeartbeats() = runTest {
        val p = twoDevices()
        pairUp(p)
        val attempts = p.lan.connectAttempts.size
        advanceTimeBy(10.minutes)
        assertTrue(online(p.mac) && online(p.tab))
        assertEquals(attempts, p.lan.connectAttempts.size, "never reconnected")
        // Both sides ping every 15 s: at 15 s … 585 s, 39 each.
        assertEquals(78, p.lan.sent.count { Codec.decode(it).getOrThrow().message == Ping })
    }

    @Test
    fun threeMissedPongsDropTheSessionAndTheTabletReconnectsWithoutRepairing() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.lan.partitioned = true
        advanceTimeBy(45.seconds)
        assertTrue(online(p.mac) && online(p.tab), "three pings out, not yet dropped")
        advanceTimeBy(16.seconds)
        assertTrue(!online(p.mac) && !online(p.tab), "dropped at the 4th tick (60 s)")

        p.lan.partitioned = false
        advanceTimeBy(MAX_WAIT)
        assertTrue(online(p.mac) && online(p.tab))
        assertEquals(1, p.mac.keyStore.peers.size)
    }

    @Test
    fun reconnectBacksOffExponentiallyUpTo30Seconds() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.lan.connectAttempts.clear()
        p.lan.stopListening(p.mac.host)
        advanceTimeBy(3.minutes)
        val gaps = p.lan.connectAttempts.zipWithNext { a, b -> (b - a) / 1000 }
        // First retry is immediate (the dropped session was the pairing one), then 1, 2, 4 … capped at 30 s.
        assertEquals(listOf(1L, 2, 4, 8, 16, 30, 30), gaps.take(7))
    }

    @Test
    fun tabletReconnectsWhenTheMacListensAgain() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.lan.partitioned = true
        advanceTimeBy(2.minutes)
        assertTrue(!online(p.tab))
        p.lan.partitioned = false
        advanceTimeBy(MAX_WAIT)
        assertTrue(online(p.mac) && online(p.tab))
    }

    @Test
    fun protocolVersionMismatchClosesWith4001() = runTest {
        val p = twoDevices()
        val raw = p.lan.dial(p.mac.host, CONTROL_PORT, "ab".repeat(32))!!
        raw.start(Ignore)
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RA", 1, Ping, v = 2)))
        runCurrent()
        assertEquals(CLOSE_VERSION_MISMATCH, raw.closeCode)
    }

    @Test
    fun helloWhoseDeviceIdIsNotTheCertificateIsDropped() = runTest {
        val p = twoDevices()
        val raw = p.lan.dial(p.mac.host, CONTROL_PORT, "ab".repeat(32))!!
        raw.start(Ignore)
        // Claims to be the Mac's paired tablet while presenting a different certificate.
        val hello = Hello(fingerprint(byteArrayOf(1)), "Impostor", Platform.ANDROID, "x", PROTOCOL_VERSION, emptyList())
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RB", 1, hello)))
        runCurrent()
        assertEquals(CLOSE_POLICY, raw.closeCode)
    }

    @Test
    fun unknownTypeGetsUnsupportedTypeAndKeepsTheConnection() = runTest {
        val p = twoDevices()
        val raw = p.lan.dial(p.mac.host, CONTROL_PORT, "ab".repeat(32))!!
        val replies = mutableListOf<String>()
        raw.start(object : ConnectionListener {
            override fun onText(text: String) {
                replies += text
            }

            override fun onClosed(reason: String) {}
        })
        raw.send("""{"v":1,"id":"01JBV0R0Z7Q4X8K3M5N6P7Q8RC","type":"future.thing","ts":1,"payload":{}}""")
        runCurrent()
        val error = replies.map { Codec.decode(it).getOrThrow() }.single { it.message is ProtocolError }
        assertEquals("unsupported_type", (error.message as ProtocolError).code)
        assertEquals("01JBV0R0Z7Q4X8K3M5N6P7Q8RC", error.replyTo)
        assertEquals(null, raw.closeCode)
    }

    @Test
    fun silentConnectionWithoutHelloIsDroppedAfterOneHeartbeat() = runTest {
        val p = twoDevices()
        val raw = p.lan.dial(p.mac.host, CONTROL_PORT, "ab".repeat(32))!!
        raw.start(Ignore)
        advanceTimeBy(16.seconds)
        assertEquals(CLOSE_POLICY, raw.closeCode)
    }

    @Test
    fun reconnectDialsAtOnceInsteadOfWaitingOutTheBackoff() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.lan.partitioned = true
        advanceTimeBy(3.minutes) // backoff is now 30 s
        p.lan.partitioned = false
        p.tab.bridge.reconnect()
        runCurrent()
        assertTrue(online(p.mac) && online(p.tab))
    }

    @Test
    fun reconnectDropsAHalfOpenSessionWithinTheProbeTimeout() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.lan.partitioned = true // the Mac slept: the tablet's socket still looks open
        p.tab.bridge.reconnect()
        advanceTimeBy(4.seconds)
        assertTrue(online(p.tab), "still waiting for the pong")
        advanceTimeBy(2.seconds)
        assertTrue(!online(p.tab), "dropped at 5 s, not 60 s")
        p.lan.partitioned = false
        advanceTimeBy(2.seconds)
        assertTrue(online(p.mac) && online(p.tab))
    }

    @Test
    fun reconnectLeavesAHealthySessionAlone() = runTest {
        val p = twoDevices()
        pairUp(p)
        val attempts = p.lan.connectAttempts.size
        p.tab.bridge.reconnect()
        advanceTimeBy(10.seconds)
        assertTrue(online(p.mac) && online(p.tab))
        assertEquals(attempts, p.lan.connectAttempts.size)
    }

    @Test
    fun followsTheMacToANewAddressFoundByMdnsAndRemembersIt() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.lan.move(p.mac.host, "10.0.1.7")
        runCurrent()
        assertTrue(online(p.mac) && online(p.tab), "dialled the new address without waiting for the backoff")
        assertEquals("10.0.1.7", p.tab.keyStore.peers.single().host)
    }

    @Test
    fun withoutMdnsTheLastKnownAddressStillWorks() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.lan.unadvertise(p.mac.id)
        p.lan.partitioned = true
        advanceTimeBy(2.minutes)
        p.lan.partitioned = false
        advanceTimeBy(MAX_WAIT)
        assertTrue(online(p.mac) && online(p.tab))
        assertEquals(p.mac.host, p.tab.keyStore.peers.single().host)
    }

    @Test
    fun aTypedAddressReplacesTheStoredOne() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.lan.unadvertise(p.mac.id)
        p.lan.move(p.mac.host, "10.0.1.7") // new address, but mDNS is blocked so nobody announces it
        advanceTimeBy(MAX_WAIT)
        assertTrue(!online(p.tab))
        p.tab.bridge.setAddress(p.mac.id, " 10.0.1.7 ")
        runCurrent()
        assertTrue(online(p.mac) && online(p.tab))
        assertEquals("10.0.1.7", p.tab.keyStore.peers.single().host)
    }

    private companion object {
        val MAX_WAIT = 31.seconds

        val Ignore = object : ConnectionListener {
            override fun onText(text: String) {}

            override fun onClosed(reason: String) {}
        }
    }
}
