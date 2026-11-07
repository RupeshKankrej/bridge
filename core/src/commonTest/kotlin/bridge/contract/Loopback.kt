package bridge.contract

import bridge.identity.fingerprint
import bridge.port.Connection
import bridge.port.ConnectionListener
import bridge.port.DiscoveredPeer
import bridge.port.DiscoveryPort
import bridge.port.KeyStorePort
import bridge.port.TransportPort
import bridge.port.TrustedPeer
import bridge.protocol.Platform
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

    internal fun forget(c: LoopConnection) = open.remove(c)
}

class LoopConnection(override val peerFingerprint: String, private val lan: Lan) : Connection {
    lateinit var other: LoopConnection
    private var listener: ConnectionListener? = null
    private val pending = mutableListOf<String>()
    var closeCode: Int? = null
        private set

    override fun start(listener: ConnectionListener) {
        this.listener = listener
        pending.forEach(listener::onText)
        pending.clear()
        if (closeCode != null) listener.onClosed("closed")
    }

    override fun send(text: String) {
        if (closeCode != null) return
        lan.sent += text
        if (!lan.partitioned) other.receive(text)
    }

    private fun receive(text: String) {
        if (closeCode != null) return
        listener?.onText(text) ?: pending.add(text)
    }

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
            lan.advertise(DiscoveredPeer(txt.getValue("id"), txt.getValue("name"), txt.getValue("platform")))

        override fun browse(onPeers: (List<DiscoveredPeer>) -> Unit) = lan.browse(onPeers)
    }

    val bridge = Bridge(name, platform, "test", keyStore, transport, discovery, scope)
    val id get() = bridge.state.value.deviceId
    val state get() = bridge.state.value
}
