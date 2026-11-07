package bridge.interop

import bridge.files.FileTransfer
import bridge.files.TransferStatus
import bridge.protocol.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * Exchanges files with the official LocalSend app over real TLS 1.3 and multicast (docs/testing.md).
 * Run with `./gradlew :core:localSendInteropTest`; it waits for you to act in LocalSend on any device
 * on the same Wi-Fi. Excluded from `allTests`.
 */
class LocalSendInteropTest {
    private val port = System.getProperty("bridge.interopPort")?.toInt() ?: 53321
    private val dir = File(System.getProperty("bridge.interopDir") ?: "build/interop").apply {
        deleteRecursively()
        mkdirs()
    }

    @Test
    fun receiveFromAndSendToLocalSend() = runBlocking {
        val keyStore = JvmKeyStore()
        // Identity is created inside FileTransfer, so TLS is built lazily on first use.
        val streams = object : bridge.port.StreamPort {
            val real by lazy { JvmStreams(keyStore.sslContext()) }

            override fun listen(port: Int, requireClientCertificate: Boolean, onStream: (bridge.port.ByteStream) -> Unit) = real.listen(port, requireClientCertificate, onStream)

            override fun dial(host: String, port: Int, callback: (bridge.port.ByteStream?, String?) -> Unit) = real.dial(host, port, callback)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val files = FileTransfer("Bridge interop", Platform.MACOS, "JVM", keyStore, streams, JvmMulticast(), DirStore(File(dir, "received")), scope, Dispatchers.IO, port, port + 2)
        scope.launch { files.state.collect { s -> s.offer?.let { files.respond(it.id, accept = true) } } }
        files.start()

        println("\n>>> In LocalSend, send any file to \"Bridge interop\" (waiting 3 minutes)\n")
        val received = withTimeout(3.minutes) {
            files.state.mapNotNull { s -> s.transfers.find { it.incoming && it.status in DONE_OR_FAILED } }.first()
        }
        assertEquals(TransferStatus.DONE, received.status, "receive from LocalSend: $received")
        received.files.forEach { assertEquals(it.size, File(dir, "received/${it.name}").length(), it.name) }
        println(">>> Received ${received.files.map { it.name }}")

        println("\n>>> Now accept \"interop.bin\" (20 MB) in LocalSend (waiting 3 minutes)\n")
        val peer = withTimeout(1.minutes) { files.state.mapNotNull { it.nearby.firstOrNull() }.first() }
        val out = File(dir, "interop.bin").apply { writeBytes(Random.nextBytes(20 * 1024 * 1024)) }
        files.send(peer, listOf(DiskFile(out)))
        val sent = withTimeout(3.minutes) {
            files.state.mapNotNull { s -> s.transfers.find { !it.incoming && it.status in DONE_OR_FAILED + TransferStatus.DECLINED } }.first()
        }
        assertEquals(TransferStatus.DONE, sent.status, "send to ${peer.alias}: $sent")
        println(">>> Sent to ${peer.alias}")
    }
}

private val DONE_OR_FAILED = setOf(TransferStatus.DONE, TransferStatus.FAILED, TransferStatus.CANCELLED)
