@file:OptIn(ExperimentalCoroutinesApi::class)

package bridge.contract

import bridge.files.FilesState
import bridge.files.TransferStatus
import bridge.files.safePath
import bridge.port.ByteStream
import bridge.port.TrustedPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FilesContractTest {
    private class Pair2(val net: StreamNet, val mac: FilesNode, val tab: FilesNode)

    private fun TestScope.nodes(tabletPresentsCertificate: Boolean = true, paired: Boolean = true): Pair2 {
        val scope: CoroutineScope = backgroundScope
        val io = StandardTestDispatcher(testScheduler)
        val net = StreamNet(scope)
        val mac = FilesNode(net, "10.0.0.1", "Mac", scope, io)
        val tab = FilesNode(net, "10.0.0.2", "Pad", scope, io, tabletPresentsCertificate)
        if (paired) {
            mac.keyStore.saveTrustedPeer(TrustedPeer(tab.id, "Pad"))
            tab.keyStore.saveTrustedPeer(TrustedPeer(mac.id, "Mac"))
        }
        mac.files.start()
        tab.files.start()
        runCurrent()
        return Pair2(net, mac, tab)
    }

    /** advanceUntilIdle ignores backgroundScope, where both nodes run; 30 s covers a 10 s drop plus retries. */
    private fun TestScope.settle() {
        advanceTimeBy(30.seconds)
        runCurrent()
    }

    private fun FilesState.peer(id: String) = assertNotNull(nearby.find { it.fingerprint == id })

    private fun FilesState.last() = transfers.last()

    @Test
    fun multicastAnnounceAndRegisterFindBothWays() = runTest {
        val p = nodes()
        assertEquals("Pad", p.mac.state.peer(p.tab.id).alias)
        assertEquals("10.0.0.2", p.mac.state.peer(p.tab.id).host)
        assertEquals("Mac", p.tab.state.peer(p.mac.id).alias)
    }

    @Test
    fun uppercaseFingerprintFromLocalSendStillMatchesItsCertificate() = runTest {
        val p = nodes(paired = false)
        // Real LocalSend 2.2 on Android announces its certificate hash in uppercase hex.
        val announce = """{"alias":"Phone","version":"2.2","deviceType":"mobile","fingerprint":"${p.tab.id.uppercase()}","port":53317,"protocol":"https","announce":true}"""
        p.net.multicast("10.0.0.2", announce.encodeToByteArray())
        runCurrent()
        assertEquals(listOf("Phone"), p.mac.state.nearby.map { it.alias }, "same device, one entry")
        p.mac.files.send(p.mac.state.peer(p.tab.id), listOf(BytesFile("x", ByteArray(3))))
        runCurrent()
        p.tab.files.respond(p.tab.state.offer!!.id, accept = true)
        settle()
        assertEquals(TransferStatus.DONE, p.mac.state.last().status, p.mac.state.last().error)
    }

    @Test
    fun pairedPeerAutoAcceptsFilesAndFolders() = runTest {
        val p = nodes()
        val a = Random.nextBytes(700_000)
        p.tab.files.send(p.tab.state.peer(p.mac.id), listOf(BytesFile("a.bin", a), BytesFile("Photos/b.txt", "hi".encodeToByteArray())))
        settle()
        assertNull(p.mac.state.offer)
        assertContentEquals(a, p.mac.store.files["a.bin"])
        assertEquals("hi", p.mac.store.files["Photos/b.txt"]?.decodeToString())
        assertEquals(TransferStatus.DONE, p.tab.state.last().status)
        assertEquals(TransferStatus.DONE, p.mac.state.last().status)
        assertEquals(listOf(700_000L, 2L), p.mac.state.last().files.map { it.done })
    }

    @Test
    fun unpairedSenderIsPromptedWithNamesAndSizes() = runTest {
        val p = nodes(tabletPresentsCertificate = false, paired = false)
        p.tab.files.send(p.tab.state.peer(p.mac.id), listOf(BytesFile("x.pdf", ByteArray(1234))))
        runCurrent()
        val offer = assertNotNull(p.mac.state.offer)
        assertEquals("Pad", offer.senderName)
        assertEquals(listOf("x.pdf" to 1234L), offer.files.map { it.name to it.size })
        p.mac.files.respond(offer.id, accept = true)
        settle()
        assertEquals(1234, p.mac.store.files["x.pdf"]?.size)
        assertEquals(TransferStatus.DONE, p.tab.state.last().status)
    }

    @Test
    fun declinedOfferReachesTheSender() = runTest {
        val p = nodes(paired = false)
        p.tab.files.send(p.tab.state.peer(p.mac.id), listOf(BytesFile("x", ByteArray(10))))
        runCurrent()
        p.mac.files.respond(p.mac.state.offer!!.id, accept = false)
        settle()
        assertEquals(TransferStatus.DECLINED, p.tab.state.last().status)
        assertTrue(p.mac.store.files.isEmpty())
    }

    @Test
    fun pairedUploadResumesAfterATenSecondWifiDrop() = runTest {
        val p = nodes()
        val big = Random.nextBytes(3_000_000)
        p.net.cutAfter = 1_200_000
        p.tab.files.send(p.tab.state.peer(p.mac.id), listOf(BytesFile("big.bin", big)))
        advanceTimeBy(5.seconds)
        assertEquals(TransferStatus.PAUSED, p.tab.state.last().files[0].status)
        settle()
        assertEquals(TransferStatus.DONE, p.tab.state.last().status)
        assertContentEquals(big, p.mac.store.files["big.bin"])
        assertEquals(0, p.mac.store.partials)
    }

    @Test
    fun unpairedUploadDoesNotResumeAndLeavesNoPartialFile() = runTest {
        val p = nodes(tabletPresentsCertificate = false, paired = false)
        p.net.cutAfter = 1_200_000
        p.tab.files.send(p.tab.state.peer(p.mac.id), listOf(BytesFile("big.bin", ByteArray(3_000_000))))
        runCurrent()
        p.mac.files.respond(p.mac.state.offer!!.id, accept = true)
        settle()
        assertEquals(TransferStatus.FAILED, p.tab.state.last().status)
        assertEquals(TransferStatus.FAILED, p.mac.state.last().status)
        assertNotNull(p.tab.state.last().error, "the sender says why")
        assertTrue(p.mac.state.last().error!!.contains("connection to Pad lost"))
        assertTrue(p.mac.store.files.isEmpty())
        assertEquals(0, p.mac.store.partials)
    }

    @Test
    fun receiverCancelDuringADropEndsBothSides() = runTest {
        val p = nodes()
        p.net.cutAfter = 1_200_000
        p.tab.files.send(p.tab.state.peer(p.mac.id), listOf(BytesFile("big.bin", ByteArray(3_000_000))))
        advanceTimeBy(5.seconds)
        p.mac.files.cancel(p.mac.state.last().id)
        settle()
        assertEquals(TransferStatus.CANCELLED, p.mac.state.last().status)
        assertEquals(TransferStatus.FAILED, p.tab.state.last().status)
        assertTrue(p.mac.store.files.isEmpty())
        assertEquals(0, p.mac.store.partials)
    }

    @Test
    fun unpairedDeviceWithACertificateIsRefusedOnThePairedPort() = runTest {
        val p = nodes(paired = false)
        val stranger = RawClient(p.net, p.mac, port = 53319, fingerprint = "f".repeat(64))
        stranger.send("POST /api/localsend/v2/prepare-upload HTTP/1.1\r\nContent-Length: 0\r\n\r\n")
        runCurrent()
        assertTrue(stranger.received().startsWith("HTTP/1.1 403"))
        assertNull(p.mac.state.offer)
    }

    @Test
    fun pairedPeerOnThePlainPortIsJustAnotherLocalSendClient() = runTest {
        val p = nodes()
        // The paired tablet's certificate isn't asked for on 53317, so it gets the prompt like anyone else.
        val tab = RawClient(p.net, p.mac, fingerprint = p.tab.id)
        val prepare = """{"info":{"alias":"Pad"},"files":{"f":{"id":"f","fileName":"a","size":1,"fileType":"x"}}}"""
        tab.send("POST /api/localsend/v2/prepare-upload HTTP/1.1\r\nContent-Length: ${prepare.length}\r\n\r\n$prepare")
        runCurrent()
        assertNotNull(p.mac.state.offer)
    }

    @Test
    fun senderPathsCannotEscapeTheReceiveFolder() {
        assertEquals("etc/passwd", safePath("../../etc/passwd"))
        assertEquals("a/b.txt", safePath("/a/./b.txt"))
        assertEquals("dir/x", safePath("dir\\..\\x"))
        assertEquals("ab", safePath("a\u0000b"))
        assertNull(safePath("../.."))
    }

    /** Byte-level: a LocalSend client's requests, as LocalSend sends them, get LocalSend's answers. */
    @Test
    fun rawLocalSendClientIsServed() = runTest {
        val p = nodes(paired = false)
        val io = RawClient(p.net, p.mac)
        val prepare = """{"info":{"alias":"Phone","version":"2.1","deviceModel":"Samsung","deviceType":"mobile",""" +
            """"fingerprint":"random","port":53317,"protocol":"https","download":false},""" +
            """"files":{"f1":{"id":"f1","fileName":"note.txt","size":5,"fileType":"text/plain","sha256":null,"preview":null,"metadata":{"modified":"2024-01-01T00:00:00Z"}}}}"""
        io.send("POST /api/localsend/v2/prepare-upload HTTP/1.1\r\nHost: x\r\nContent-Length: ${prepare.length}\r\n\r\n$prepare")
        runCurrent()
        p.mac.files.respond(p.mac.state.offer!!.id, accept = true)
        runCurrent()
        val response = io.received()
        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"), response)
        assertTrue("X-Bridge-Resume" !in response, "no Bridge extension for LocalSend clients")
        val session = Regex("\"sessionId\":\"(\\w+)\"").find(response)!!.groupValues[1]
        val token = Regex("\"f1\":\"(\\w+)\"").find(response)!!.groupValues[1]
        // Chunked body, keep-alive on the same connection.
        io.send("POST /api/localsend/v2/upload?sessionId=$session&fileId=f1&token=$token HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nhel\r\n2\r\nlo\r\n0\r\n\r\n")
        runCurrent()
        assertTrue(io.received().startsWith("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n"))
        assertEquals("hello", p.mac.store.files["note.txt"]?.decodeToString())
        io.send("POST /api/localsend/v2/upload?sessionId=$session&fileId=f1&token=wrong HTTP/1.1\r\nContent-Length: 0\r\n\r\n")
        runCurrent()
        assertTrue(io.received().startsWith("HTTP/1.1 403"))
    }

    @Test
    fun infoAnswersWithLocalSendFields() = runTest {
        val p = nodes()
        val io = RawClient(p.net, p.mac)
        io.send("GET /api/localsend/v2/info HTTP/1.1\r\n\r\n")
        runCurrent()
        val body = io.received().substringAfter("\r\n\r\n")
        assertEquals("""{"alias":"Mac","version":"2.1","deviceType":"desktop","fingerprint":"${p.mac.id}","download":false}""", body)
    }
}

/** Speaks raw HTTP to [server]; by default on 53317 without a client certificate, like the LocalSend app. */
private class RawClient(net: StreamNet, server: FilesNode, port: Int = 53317, fingerprint: String? = null) {
    private lateinit var stream: ByteStream
    private val inbox = StringBuilder()

    init {
        net.connect("10.0.0.9", fingerprint, server.host, port) { s, _ -> stream = s!! }
        pump()
    }

    private fun pump() {
        stream.read(1 shl 20) { data, _ ->
            if (data != null && data.isNotEmpty()) {
                inbox.append(data.decodeToString())
                pump()
            }
        }
    }

    fun send(text: String) = stream.write(text.encodeToByteArray()) {}

    fun received(): String = inbox.toString().also { inbox.clear() }
}
