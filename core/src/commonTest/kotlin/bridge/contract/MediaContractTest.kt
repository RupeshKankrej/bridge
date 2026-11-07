@file:OptIn(ExperimentalCoroutinesApi::class)

package bridge.contract

import bridge.protocol.BatteryState
import bridge.protocol.Codec
import bridge.protocol.Envelope
import bridge.protocol.Hello
import bridge.protocol.MediaAction
import bridge.protocol.MediaCommand
import bridge.protocol.MediaState
import bridge.protocol.PROTOCOL_VERSION
import bridge.protocol.Platform
import bridge.protocol.ShareText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MediaContractTest {
    private val song = MediaState("Spotify", "Song", "Artist", playing = true, position = 10.0, duration = 200.0, volume = 60)

    private fun TestScope.watching(): Devices {
        val p = twoDevices()
        pairUp(p)
        p.tab.bridge.watchMedia(true)
        runCurrent()
        return p
    }

    private fun Devices.macMedia() = tab.state.paired.single().media

    private fun Devices.mediaStatesSent() = lan.sent.count { "\"media.state\"" in it }

    @Test
    fun theMacPollsOnlyWhileTheTabletWatches() = runTest {
        val p = twoDevices()
        pairUp(p)
        assertNull(p.mac.media.watcher)
        p.tab.bridge.watchMedia(true)
        runCurrent()
        assertNotNull(p.mac.media.watcher)
        p.tab.bridge.watchMedia(false)
        runCurrent()
        assertNull(p.mac.media.watcher)
    }

    @Test
    fun nowPlayingReachesTheTabletAndOnlyRealChangesAreResent() = runTest {
        val p = watching()
        p.mac.media.report(song)
        runCurrent()
        assertEquals(song, p.macMedia())
        // The position ticking along as expected isn't news; the tablet advances it itself.
        p.mac.media.report(song.copy(position = 10.5))
        runCurrent()
        assertEquals(1, p.mediaStatesSent())
        // A seek, a pause and a new volume are.
        p.mac.media.report(song.copy(position = 90.0))
        p.mac.media.report(song.copy(position = 90.0, playing = false))
        p.mac.media.report(song.copy(position = 90.0, playing = false, volume = 20))
        runCurrent()
        assertEquals(4, p.mediaStatesSent())
        assertEquals(20, p.macMedia()?.volume)
    }

    @Test
    fun aClosedSessionStopsThePolling() = runTest {
        val p = watching()
        p.tab.bridge.unpair(p.mac.id)
        runCurrent()
        assertNull(p.mac.media.watcher)
    }

    @Test
    fun aReconnectingTabletWatchesAgain() = runTest {
        val p = watching()
        p.lan.move(p.mac.host, "10.0.0.9")
        advanceTimeBy(5.seconds)
        assertNotNull(p.mac.media.watcher)
        assertEquals(2, p.lan.sent.count { "\"watch\"" in it })
    }

    @Test
    fun commandsReachThePlayerAndABadVolumeIsRefused() = runTest {
        val p = watching()
        p.tab.bridge.media(MediaAction.PAUSE)
        p.tab.bridge.media(MediaAction.VOLUME, 150) // clamped by the sender
        p.tab.bridge.media(MediaAction.WATCH) // not a player command; ignored
        runCurrent()
        assertEquals(listOf(MediaCommand(MediaAction.PAUSE), MediaCommand(MediaAction.VOLUME, 100)), p.mac.media.commands)

        val (raw, replies) = rawPeer(p, p.tab.id)
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RA", 1, Hello(p.tab.id, "Pad 3", Platform.ANDROID, "x", PROTOCOL_VERSION, emptyList()))))
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RB", 2, MediaCommand(MediaAction.VOLUME))))
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RC", 3, MediaCommand(MediaAction.VOLUME, -1))))
        runCurrent()
        assertEquals(2, p.mac.media.commands.size)
        assertEquals(listOf("invalid_message", "invalid_message"), replies.errors().map { it.code })
    }

    @Test
    fun aRefusedCommandTellsTheTabletWhy() = runTest {
        val p = watching()
        p.mac.media.allowed = false
        p.tab.bridge.media(MediaAction.PLAY)
        runCurrent()
        assertTrue(p.tab.state.message!!.contains("Accessibility"), p.tab.state.message)
        p.mac.media.allowed = true
        p.tab.bridge.media(MediaAction.PLAY)
        runCurrent()
        assertEquals(listOf(MediaCommand(MediaAction.PLAY)), p.mac.media.commands)
    }

    @Test
    fun batteryShowsOnTheMacAndIsResentOnReconnect() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.tab.bridge.battery(BatteryState(82, charging = false))
        runCurrent()
        assertEquals(BatteryState(82, false), p.mac.state.paired.single().battery)
        p.lan.move(p.mac.host, "10.0.0.9")
        advanceTimeBy(5.seconds)
        assertEquals(BatteryState(82, false), p.mac.state.paired.single().battery)
        assertEquals(2, p.lan.sent.count { "battery.state" in it })
    }

    @Test
    fun aSharedLinkOpensOnTheMacAndTextStaysText() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.tab.bridge.share("  https://example.com/a?b=c  ")
        p.tab.bridge.share("hello https://example.com")
        runCurrent()
        assertEquals(listOf(ShareText(url = "https://example.com/a?b=c"), ShareText(text = "hello https://example.com")), p.mac.share.opened)
    }

    @Test
    fun aTabletWithReceivingOffSaysSo() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.tab.share.enabled = false
        p.mac.bridge.share("https://example.com")
        runCurrent()
        assertTrue("turned off" in p.mac.state.message!!)
    }

    @Test
    fun onlyWebLinksAreOpened() = runTest {
        val p = twoDevices()
        pairUp(p)
        val (raw, replies) = rawPeer(p, p.tab.id)
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RA", 1, Hello(p.tab.id, "Pad 3", Platform.ANDROID, "x", PROTOCOL_VERSION, emptyList()))))
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RB", 2, ShareText(url = "file:///etc/passwd"))))
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RC", 3, ShareText(url = "javascript:alert(1)"))))
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RD", 4, ShareText(text = "a", url = "https://x.y"))))
        raw.send(Codec.encode(Envelope("01JBV0R0Z7Q4X8K3M5N6P7Q8RE", 5, ShareText())))
        runCurrent()
        assertEquals(emptyList(), p.mac.share.opened)
        assertEquals(List(4) { "invalid_message" }, replies.errors().map { it.code })
    }
}
