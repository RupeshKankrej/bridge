package bridge.contract

import bridge.identity.fingerprint
import bridge.port.ClipboardPort
import bridge.port.Connection
import bridge.port.ConnectionListener
import bridge.port.DiscoveredPeer
import bridge.port.DiscoveryPort
import bridge.port.InputPort
import bridge.port.KeyStorePort
import bridge.port.MediaPort
import bridge.port.NotificationEvents
import bridge.port.NotificationPort
import bridge.port.SharePort
import bridge.port.TransportPort
import bridge.port.TrustedPeer
import bridge.protocol.InputKey
import bridge.protocol.InputPointer
import bridge.protocol.MediaCommand
import bridge.protocol.MediaState
import bridge.protocol.NotifPosted
import bridge.protocol.Platform
import bridge.protocol.ShareText
import bridge.session.Bridge
import kotlinx.coroutines.CoroutineScope
import kotlin.random.Random

/**
 * In-memory stand-in for the LAN and the platform TLS WebSocket, so contract tests drive two
 * real [Bridge]s with virtual time. TLS itself is exercised on the devices (docs/testing.md).
 */
class Lan(val clock: () -> Long) {
    private val servers = mutableMapOf<Pair<String, Int>, Pair<() -> String, (Connection) -> Unit>>()
    private val open = mutableListOf<LoopConnection>()
    private val adverts = mutableMapOf<String, DiscoveredPeer>()
    private val browsers = mutableListOf<(List<DiscoveredPeer>) -> Unit>()

    /** Frames still "send" but never arrive, like a Wi-Fi drop that leaves both sockets half-open. */
    var partitioned = false
    val connectAttempts = mutableListOf<Long>()
    val sent = mutableListOf<String>()

    fun listen(host: String, port: Int, fingerprint: () -> String, accept: (Connection) -> Unit) {
        servers[host to port] = fingerprint to accept
    }

    fun stopListening(host: String) {
        servers.keys.removeAll { it.first == host }
        open.toList().forEach { it.close(1001, "going away") }
    }

    /** Opens a connection presenting [fingerprint] as the client certificate; null if refused. */
    fun dial(host: String, port: Int, fingerprint: String): LoopConnection? {
        connectAttempts += clock()
        val (serverFingerprint, accept) = servers[host to port] ?: return null
        if (partitioned) return null
        val client = LoopConnection(serverFingerprint(), this)
        val server = LoopConnection(fingerprint, this)
        client.other = server
        server.other = client
        open += client
        open += server
        accept(server)
        return client
    }

    fun advertise(peer: DiscoveredPeer) {
        adverts[peer.id] = peer
        browsers.forEach { it(adverts.values.toList()) }
    }

    fun browse(onPeers: (List<DiscoveredPeer>) -> Unit) {
        browsers += onPeers
        onPeers(adverts.values.toList())
    }

    /** The device at [from] gets a new address (DHCP on another network): its sockets die and mDNS announces [to]. */
    fun move(from: String, to: String) {
        servers.keys.filter { it.first == from }.forEach { key -> servers[to to key.second] = servers.remove(key)!! }
        open.toList().forEach { it.close(1001, "network changed") }
        adverts.entries.forEach { e -> if (e.value.host == from) e.setValue(e.value.copy(host = to)) }
        browsers.forEach { it(adverts.values.toList()) }
    }

    /** mDNS stops answering for [id] (blocked or slow on this network). */
    fun unadvertise(id: String) {
        adverts.remove(id)
        browsers.forEach { it(adverts.values.toList()) }
    }

    internal fun forget(c: LoopConnection) = open.remove(c)
}

class LoopConnection(override val peerFingerprint: String, private val lan: Lan) : Connection {
    lateinit var other: LoopConnection
    private var listener: ConnectionListener? = null
    /** Frames that arrived before [start]: String for text, ByteArray for binary. */
    private val pending = mutableListOf<Any>()
    var closeCode: Int? = null
        private set

    override fun start(listener: ConnectionListener) {
        this.listener = listener
        pending.forEach { deliver(listener, it) }
        pending.clear()
        if (closeCode != null) listener.onClosed("closed")
    }

    override fun send(text: String) {
        if (closeCode != null) return
        lan.sent += text
        if (!lan.partitioned) other.receive(text)
    }

    override fun sendBinary(bytes: ByteArray) {
        if (closeCode != null) return
        if (!lan.partitioned) other.receive(bytes)
    }

    private fun receive(frame: Any) {
        if (closeCode != null) return
        listener?.let { deliver(it, frame) } ?: pending.add(frame)
    }

    private fun deliver(listener: ConnectionListener, frame: Any) =
        if (frame is ByteArray) listener.onBinary(frame) else listener.onText(frame as String)

    override fun close(code: Int, reason: String) {
        if (closeCode != null) return
        closed(code, reason)
        if (!lan.partitioned) other.closed(code, reason)
    }

    private fun closed(code: Int, reason: String) {
        if (closeCode != null) return
        closeCode = code
        lan.forget(this)
        listener?.onClosed(reason)
    }
}

class FakeKeyStore : KeyStorePort {
    private var certificate: ByteArray? = null
    val peers = mutableListOf<TrustedPeer>()

    override fun loadCertificate() = certificate

    // Stand-ins: contract tests only need distinct certificates; CertificateTest checks real crypto on the JVM.
    override fun generateKey() = Random.nextBytes(91)

    override fun sign(data: ByteArray) = Random.nextBytes(72)

    override fun saveCertificate(certificate: ByteArray) {
        this.certificate = certificate
    }

    override fun trustedPeers() = peers.toList()

    override fun saveTrustedPeer(peer: TrustedPeer) {
        peers.removeAll { it.fingerprint == peer.fingerprint }
        peers += peer
    }

    override fun removeTrustedPeer(fingerprint: String) {
        peers.removeAll { it.fingerprint == fingerprint }
    }
}

/**
 * Worst case for echo: no origin tag, so our own writes come back as local copies, the way they
 * would if a platform lost the tag. Core must still not echo them.
 */
class FakeClipboard : ClipboardPort {
    var text: String? = null
        private set
    val writes = mutableListOf<String>()
    private var onLocalChange: (String) -> Unit = {}

    override fun watch(onLocalChange: (String) -> Unit) {
        this.onLocalChange = onLocalChange
    }

    override fun write(text: String) {
        writes += text
        copy(text)
    }

    /** The user copies [text] on this device. */
    fun copy(text: String) {
        this.text = text
        onLocalChange(text)
    }
}

/** Records what core asked the platform to do; [events] stands in for the user and the OS. */
class FakeNotifications : NotificationPort {
    lateinit var events: NotificationEvents
    val shown = mutableListOf<Pair<NotifPosted, ByteArray?>>()
    val removed = mutableListOf<String>()
    val actions = mutableListOf<Pair<String, Int>>()
    val replies = mutableListOf<Pair<String, String>>()

    override fun watch(events: NotificationEvents) {
        this.events = events
    }

    override fun show(notification: NotifPosted, icon: ByteArray?) {
        shown += notification to icon
    }

    override fun remove(key: String) {
        removed += key
    }

    override fun action(key: String, index: Int) {
        actions += key to index
    }

    override fun reply(key: String, text: String) {
        replies += key to text
    }
}

/** Records injected input; [allowed] = false is the Mac without the Accessibility permission. */
class FakeInput : InputPort {
    var allowed = true
    val received = mutableListOf<Any>()

    override fun pointer(event: InputPointer) = allowed.also { if (it) received += event }

    override fun key(event: InputKey) = allowed.also { if (it) received += event }
}

/** The Mac's players: [report] stands in for a poll; [watcher] is non-null while core has polling on. */
class FakeMedia : MediaPort {
    var watcher: ((MediaState) -> Unit)? = null
    val commands = mutableListOf<MediaCommand>()

    override fun watch(onState: ((MediaState) -> Unit)?) {
        watcher = onState
    }

    override fun command(command: MediaCommand) {
        commands += command
    }

    fun report(state: MediaState) = watcher!!(state)
}

/** [enabled] = false is the tablet with receiving turned off. */
class FakeShare : SharePort {
    var enabled = true
    val opened = mutableListOf<ShareText>()

    override fun open(share: ShareText) = enabled.also { if (it) opened += share }
}

class Device(val lan: Lan, val host: String, name: String, platform: Platform, scope: CoroutineScope) {
    val keyStore = FakeKeyStore()
    private val myFingerprint by lazy { fingerprint(keyStore.loadCertificate()!!) }

    private val transport = object : TransportPort {
        override fun listen(port: Int, onConnection: (Connection) -> Unit) = lan.listen(host, port, { myFingerprint }, onConnection)

        override fun connect(host: String, port: Int, callback: (Connection?, String?) -> Unit) {
            val c = lan.dial(host, port, myFingerprint)
            callback(c, if (c == null) "refused" else null)
        }
    }

    private val discovery = object : DiscoveryPort {
        override fun advertise(port: Int, txt: Map<String, String>) =
            lan.advertise(DiscoveredPeer(txt.getValue("id"), txt.getValue("name"), txt.getValue("platform"), host, port))

        override fun browse(onPeers: (List<DiscoveredPeer>) -> Unit) = lan.browse(onPeers)
    }

    val clipboard = FakeClipboard()
    val notifications = FakeNotifications()
    val input = FakeInput()
    val media = FakeMedia()
    val share = FakeShare()
    val bridge = Bridge(name, platform, "test", keyStore, transport, discovery, clipboard, notifications, input, media, share, scope)
    val id get() = bridge.state.value.deviceId
    val state get() = bridge.state.value
}
