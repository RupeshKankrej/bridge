package bridge.contract

import bridge.files.FileTransfer
import bridge.identity.fingerprint
import bridge.port.FileSink
import bridge.port.FileSource
import bridge.port.FileStorePort
import bridge.port.MulticastPort
import bridge.port.OutgoingFile
import bridge.port.Stream
import bridge.port.StreamPort
import bridge.protocol.Platform
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * In-memory stand-in for TLS streams and LocalSend multicast. [cutAfter] simulates a Wi-Fi drop: once
 * that many upload bytes have gone through, every open stream breaks and connects fail for [dropFor].
 */
class StreamNet(private val scope: CoroutineScope) {
    private val servers = mutableMapOf<Pair<String, Int>, Pair<String, (Stream) -> Unit>>()
    private val members = mutableListOf<Pair<String, (ByteArray, String) -> Unit>>()
    private val open = mutableListOf<Pipe>()
    var offline = false
    var cutAfter: Long? = null
    var dropFor: Duration = 10.seconds
    private var uploaded = 0L

    fun listen(host: String, port: Int, fingerprint: String, onStream: (Stream) -> Unit) {
        servers[host to port] = fingerprint to onStream
    }

    fun connect(fromHost: String, clientFingerprint: String?, host: String, port: Int, callback: (Stream?, String?) -> Unit) {
        val (serverFingerprint, accept) = servers[host to port] ?: return callback(null, "refused")
        if (offline) return callback(null, "network unreachable")
        val client = Pipe(serverFingerprint, host, this, upload = true)
        val server = Pipe(clientFingerprint, fromHost, this, upload = false)
        client.other = server
        server.other = client
        open += client
        open += server
        accept(server)
        callback(client, null)
    }

    fun join(host: String, onDatagram: (ByteArray, String) -> Unit) {
        members += host to onDatagram
    }

    fun multicast(from: String, data: ByteArray) = members.filter { it.first != from }.forEach { it.second(data, from) }

    /** Returns false if this write tripped the drop. */
    internal fun counted(p: Pipe, size: Int): Boolean {
        if (!p.upload) return true
        uploaded += size
        val limit = cutAfter ?: return true
        if (uploaded < limit) return true
        cutAfter = null
        offline = true
        open.toList().forEach { it.cut() }
        scope.launch {
            delay(dropFor)
            offline = false
        }
        return false
    }

    internal fun forget(p: Pipe) = open.remove(p)
}

class Pipe(override val peerFingerprint: String?, override val remoteHost: String, private val net: StreamNet, val upload: Boolean) : Stream {
    lateinit var other: Pipe
    private val inbox = ArrayDeque<ByteArray>()
    private var eof = false
    private var broken = false
    private var pending: Pair<Int, (ByteArray?, String?) -> Unit>? = null

    override fun read(max: Int, callback: (ByteArray?, String?) -> Unit) {
        pending = max to callback
        flush()
    }

    private fun flush() {
        val (max, callback) = pending ?: return
        when {
            broken -> callback(null, "connection reset")
            inbox.isNotEmpty() -> {
                val head = inbox.removeFirst()
                if (head.size > max) inbox.addFirst(head.copyOfRange(max, head.size))
                pending = null
                return callback(head.copyOf(minOf(max, head.size)), null)
            }
            eof -> callback(ByteArray(0), null)
            else -> return
        }
        pending = null
    }

    override fun write(data: ByteArray, callback: (String?) -> Unit) {
        if (broken || eof) return callback("connection reset")
        if (!net.counted(this, data.size)) return callback("connection reset")
        other.inbox.addLast(data.copyOf())
        other.flush()
        callback(null)
    }

    override fun close() {
        if (eof) return
        eof = true
        net.forget(this)
        flush()
        other.eof = true
        other.flush()
    }

    fun cut() {
        broken = true
        net.forget(this)
        flush()
    }
}

/** The receive folder in memory. */
class MemoryStore : FileStorePort {
    val files = mutableMapOf<String, ByteArray>()
    var partials = 0
        private set

    override fun create(path: String): FileSink {
        partials++
        return object : FileSink {
            private val bytes = mutableListOf<ByteArray>()

            override fun write(data: ByteArray) {
                bytes += data
            }

            override fun close(keep: Boolean) {
                partials--
                if (keep) files[path] = bytes.fold(ByteArray(0)) { acc, b -> acc + b }
            }
        }
    }
}

class BytesFile(override val path: String, val bytes: ByteArray, override val mime: String = "application/octet-stream") : OutgoingFile {
    override val size get() = bytes.size.toLong()

    override fun open(offset: Long): FileSource = object : FileSource {
        var at = offset.toInt()

        override fun read(max: Int) = bytes.copyOfRange(at, minOf(bytes.size, at + max)).also { at += it.size }

        override fun close() = Unit
    }
}

/** One device's file side. [presentsCertificate] = false behaves like a LocalSend client (no client certificate). */
class FilesNode(
    val net: StreamNet,
    val host: String,
    name: String,
    scope: CoroutineScope,
    io: CoroutineDispatcher,
    presentsCertificate: Boolean = true,
) {
    val keyStore = FakeKeyStore()
    val store = MemoryStore()
    private var myId = ""

    private val streams = object : StreamPort {
        override fun listen(port: Int, onStream: (Stream) -> Unit) = net.listen(host, port, myId, onStream)

        override fun connect(host: String, port: Int, callback: (Stream?, String?) -> Unit) =
            net.connect(this@FilesNode.host, myId.takeIf { presentsCertificate }, host, port, callback)
    }

    private val multicast = object : MulticastPort {
        override fun join(onDatagram: (ByteArray, String) -> Unit) = net.join(host, onDatagram)

        override fun send(data: ByteArray) = net.multicast(host, data)
    }

    val files = FileTransfer(name, Platform.MACOS, null, keyStore, streams, multicast, store, scope, io)
    val id = fingerprint(keyStore.loadCertificate()!!).also { myId = it }
    val state get() = files.state.value
}
