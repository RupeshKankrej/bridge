@file:OptIn(ExperimentalCoroutinesApi::class)

package bridge.contract

import bridge.protocol.ClipboardSet
import bridge.protocol.Codec
import bridge.protocol.MAX_CLIPBOARD_BYTES
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ClipboardContractTest {
    private fun Lan.clipboardSets() = sent.count { Codec.decode(it).getOrThrow().message is ClipboardSet }

    @Test
    fun macCopyReachesTheTabletOnceAndIsNotEchoedBack() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.mac.clipboard.copy("hello")
        runCurrent()
        assertEquals(listOf("hello"), p.tab.clipboard.writes)
        assertEquals(emptyList(), p.mac.clipboard.writes)
        assertEquals(1, p.lan.clipboardSets())
    }

    @Test
    fun tabletCopyReachesTheMacAndTheNewestCopyWins() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.mac.clipboard.copy("one")
        runCurrent()
        p.tab.clipboard.copy("two")
        runCurrent()
        p.mac.clipboard.copy("three")
        runCurrent()
        assertEquals(listOf("two"), p.mac.clipboard.writes)
        assertEquals(listOf("one", "three"), p.tab.clipboard.writes)
        assertEquals(3, p.lan.clipboardSets())
    }

    @Test
    fun copiesMadeWhileApartConvergeOnReconnect() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.lan.partitioned = true
        p.mac.clipboard.copy("from mac")
        p.tab.clipboard.copy("from tablet")
        runCurrent()
        advanceTimeBy(61.seconds) // heartbeat drops the session
        p.lan.partitioned = false
        advanceTimeBy(31.seconds) // tablet reconnects; both send clipboard.request
        val mac = assertNotNull(p.mac.clipboard.text)
        assertEquals(mac, p.tab.clipboard.text, "both sides keep the same winner")
    }

    @Test
    fun textOverOneMegabyteIsNotSent() = runTest {
        val p = twoDevices()
        pairUp(p)
        p.mac.clipboard.copy("x".repeat(MAX_CLIPBOARD_BYTES + 1))
        runCurrent()
        assertEquals(0, p.lan.clipboardSets())
        assertTrue(p.mac.state.message!!.contains("1 MB"))
    }
}
