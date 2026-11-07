@file:OptIn(ExperimentalCoroutinesApi::class)

package bridge.contract

import bridge.protocol.Platform
import bridge.session.Pairing
import bridge.session.PeerStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

internal class Devices(val lan: Lan, val mac: Device, val tab: Device)

internal fun TestScope.twoDevices(): Devices {
    val lan = Lan { testScheduler.currentTime }
    val mac = Device(lan, "10.0.0.2", "MacBook", Platform.MACOS, backgroundScope)
    val tab = Device(lan, "10.0.0.3", "Pad 3", Platform.ANDROID, backgroundScope)
    mac.bridge.start()
    tab.bridge.start()
    runCurrent()
    return Devices(lan, mac, tab)
}

/** Opens pairing on the Mac, scans on the tablet, confirms. */
internal fun TestScope.pairUp(p: Devices) {
    p.mac.bridge.openPairing(p.mac.host)
    runCurrent()
    p.tab.bridge.pair(p.mac.state.invite!!.toUri())
    runCurrent()
    p.tab.bridge.confirmPairing()
    runCurrent()
}

class PairingContractTest {
    @Test
    fun discoversPeersButNotItself() = runTest {
        val p = twoDevices()
        assertEquals(listOf(p.tab.id), p.mac.state.discovered.map { it.id })
        assertEquals("android", p.mac.state.discovered.single().platform)
    }

    @Test
    fun qrPairingShowsTheSameCodeAndStoresTrustOnBothSides() = runTest {
        val p = twoDevices()
        p.mac.bridge.openPairing(p.mac.host)
        runCurrent()
        val invite = assertNotNull(p.mac.state.invite)
        assertEquals(p.mac.id, invite.fingerprint)

        p.tab.bridge.pair(invite.toUri())
        runCurrent()
        assertEquals(Pairing("MacBook", invite.code, awaitingLocalConfirm = true), p.tab.state.pairing)
        assertEquals(Pairing("Pad 3", invite.code, awaitingLocalConfirm = false), p.mac.state.pairing)
        assertTrue(p.mac.keyStore.peers.isEmpty(), "no trust before the user confirms")

        p.tab.bridge.confirmPairing()
        runCurrent()
        assertEquals(listOf(PeerStatus(p.mac.id, "MacBook", online = true)), p.tab.state.paired)
        assertEquals(listOf(PeerStatus(p.tab.id, "Pad 3", online = true)), p.mac.state.paired)
        assertNull(p.mac.state.pairing)
        assertNull(p.tab.state.pairing)
        assertNull(p.mac.state.invite, "QR disappears once paired")
        // Only the scanner keeps an address: it is the side that reconnects.
        assertEquals("10.0.0.2", p.tab.keyStore.peers.single().host)
        assertNull(p.mac.keyStore.peers.single().host)
    }

    @Test
    fun wrongCodeIsRefusedAndBurnsTheCode() = runTest {
        val p = twoDevices()
        p.mac.bridge.openPairing(p.mac.host)
        runCurrent()
        val invite = p.mac.state.invite!!
        val wrong = ((invite.code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

        p.tab.bridge.pair(invite.copy(code = wrong).toUri())
        runCurrent()
        assertNull(p.tab.state.pairing)
        assertTrue(p.mac.state.paired.isEmpty() && p.tab.state.paired.isEmpty())
        assertNotNull(p.mac.state.message)
        assertNotEquals(invite.code, p.mac.state.invite!!.code, "code rotates after a failed attempt")

        // The old, once-correct code is now useless too.
        p.tab.bridge.pair(invite.toUri())
        runCurrent()
        assertNull(p.mac.state.pairing)
    }

    @Test
    fun qrPointingAtAnotherDeviceIsRefusedBeforeSendingAnything() = runTest {
        val p = twoDevices()
        p.mac.bridge.openPairing(p.mac.host)
        runCurrent()
        val sentBefore = p.lan.sent.size
        p.tab.bridge.pair(p.mac.state.invite!!.copy(fingerprint = "0".repeat(64)).toUri())
        runCurrent()
        assertEquals(sentBefore, p.lan.sent.size, "the tablet hung up before sending anything")
        assertNull(p.tab.state.pairing)
        assertTrue(p.tab.state.message!!.contains("isn't the one in the QR code"))
    }

    @Test
    fun malformedQrIsRejected() = runTest {
        val p = twoDevices()
        p.tab.bridge.pair("bridge://pair?fp=zz&host=10.0.0.2&port=53318&code=12")
        runCurrent()
        assertTrue(p.lan.connectAttempts.isEmpty())
        assertNotNull(p.tab.state.message)
    }

    @Test
    fun cancelOnTheTabletLeavesNoTrust() = runTest {
        val p = twoDevices()
        p.mac.bridge.openPairing(p.mac.host)
        runCurrent()
        p.tab.bridge.pair(p.mac.state.invite!!.toUri())
        runCurrent()
        p.tab.bridge.rejectPairing()
        runCurrent()
        assertNull(p.mac.state.pairing)
        assertTrue(p.mac.keyStore.peers.isEmpty() && p.tab.keyStore.peers.isEmpty())
        assertEquals("Pad 3 declined pairing", p.mac.state.message)
    }

    @Test
    fun unpairOnTheMacMakesTheTabletForgetItOnReconnect() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.mac.bridge.unpair(p.tab.id)
        runCurrent()
        assertTrue(p.mac.state.paired.isEmpty())

        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(p.tab.state.paired.isEmpty())
        assertTrue(p.tab.state.message!!.contains("no longer trusts"))
        val attempts = p.lan.connectAttempts.size
        advanceTimeBy(120.seconds)
        assertEquals(attempts, p.lan.connectAttempts.size, "stops dialling a peer it no longer trusts")
    }
}
