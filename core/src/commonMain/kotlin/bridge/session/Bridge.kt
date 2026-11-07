package bridge.session

import bridge.identity.Identity
import bridge.identity.secureRandomBytes
import bridge.port.ClipboardPort
import bridge.port.Connection
import bridge.port.ConnectionListener
import bridge.port.DiscoveredPeer
import bridge.port.DiscoveryPort
import bridge.port.KeyStorePort
import bridge.port.NotificationEvents
import bridge.port.NotificationPort
import bridge.port.TransportPort
import bridge.port.TrustedPeer
import bridge.protocol.ClipboardRequest
import bridge.protocol.ClipboardSet
import bridge.protocol.Codec
import bridge.protocol.Envelope
import bridge.protocol.Hello
import bridge.protocol.MAX_CLIPBOARD_BYTES
import bridge.protocol.MAX_ICON_BYTES
import bridge.protocol.Message
import bridge.protocol.NotifAction
import bridge.protocol.NotifDismissed
import bridge.protocol.NotifPosted
import bridge.protocol.NotifReply
import bridge.protocol.PNG_MIME
import bridge.protocol.PROTOCOL_VERSION
import bridge.protocol.PairConfirm
import bridge.protocol.PairReject
import bridge.protocol.PairRequest
import bridge.protocol.Ping
import bridge.protocol.Platform
import bridge.protocol.Pong
import bridge.protocol.ProtocolError
import bridge.protocol.TEXT_MIME
import bridge.protocol.UnsupportedTypeException
import bridge.protocol.newUlid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Control channel port: LocalSend's 53317 + 1 (spec §3). */
const val CONTROL_PORT = 53318
const val CLOSE_NORMAL = 1000
const val CLOSE_POLICY = 1008
const val CLOSE_VERSION_MISMATCH = 4001

internal val HEARTBEAT_INTERVAL = 15.seconds
internal const val MAX_MISSED_PONGS = 3
internal val MAX_BACKOFF = 30.seconds

/** After a wake or network change, a live-looking session that doesn't answer a ping within this is dead. */
internal val PROBE_TIMEOUT = 5.seconds

data class BridgeState(
    /** This device's certificate fingerprint. */
    val deviceId: String,
    val discovered: List<DiscoveredPeer> = emptyList(),
    val paired: List<PeerStatus> = emptyList(),
    /** Host side: the invite to show as a QR code while pairing is open. */
    val invite: PairingInvite? = null,
    /** Set once the QR has been scanned and the code checked; both sides show [Pairing.code]. */
    val pairing: Pairing? = null,
    /** Last failure, worded for the user (spec §7). */
    val message: String? = null,
)

/** [host] is the address this side dials, or null on the side that only accepts. */
data class PeerStatus(val fingerprint: String, val name: String, val online: Boolean, val host: String? = null)

/** [awaitingLocalConfirm]: this side (the scanner) shows Confirm / Cancel; otherwise it waits for the peer. */
data class Pairing(val peerName: String, val code: String, val awaitingLocalConfirm: Boolean)

/**
 * One device's view of the control channel: listens and advertises, dials paired peers with
 * backoff, and runs the per-connection session state machine (spec §4, §5.1):
 *
 *   connect → hello exchange → paired (peer's fingerprint is trusted)
 *                            → unpaired → pair.request → pair.confirm ×2 → paired
 *
 * Every connection pings every 15 s and is dropped after 3 unanswered pings.
 * All state lives on [scope]'s dispatcher (Main in the apps), so nothing here needs locks.
 */
class Bridge internal constructor(
    private val name: String,
    private val platform: Platform,
    private val appVersion: String,
    private val keyStore: KeyStorePort,
    private val transport: TransportPort,
    private val discovery: DiscoveryPort,
    private val clipboard: ClipboardPort,
    private val notifications: NotificationPort,
    private val scope: CoroutineScope,
) {
    constructor(
        name: String,
        platform: Platform,
        appVersion: String,
        keyStore: KeyStorePort,
        transport: TransportPort,
        discovery: DiscoveryPort,
        clipboard: ClipboardPort,
        notifications: NotificationPort,
    ) : this(name, platform, appVersion, keyStore, transport, discovery, clipboard, notifications, CoroutineScope(SupervisorJob() + Dispatchers.Main))

    private val identity = Identity.loadOrCreate(keyStore)
    private val myId = identity.fingerprint

    private val _state = MutableStateFlow(BridgeState(myId))
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    private class Session(val conn: Connection, val invite: PairingInvite?) {
        /** Text frames as String, binary frames as ByteArray, in arrival order. */
        val incoming = Channel<Any>(Channel.UNLIMITED)

        /** A message whose `binaryLength` frame hasn't arrived yet. */
        var awaitingBinary: Envelope? = null
        var peer: Hello? = null
        var paired = false
        var missedPongs = 0
        var pongs = 0
        var pairingCode: String? = null
        var confirmSent = false
    }

    private val online = mutableMapOf<String, Session>()
    private var pairingSession: Session? = null
    private val dialers = mutableMapOf<String, Job>()

    /** Where mDNS last saw each peer; unauthenticated, so it's only a dial target until the pinned TLS handshake succeeds. */
    private val located = mutableMapOf<String, Pair<String, Int>>()
    private var started = false

    /**
     * The newest clipboard text this device copied or applied, for last-writer-wins (spec §5.2).
     * [origin] is the copying device's ID and breaks [ts] ties so both sides pick the same winner.
     * Starts as a contentless floor at launch: we can't know how old the OS clipboard is, so a peer's
     * item copied before we started never overwrites it.
     */
    private class Clip(val text: String?, val ts: Long, val origin: String)

    private var clip = Clip(null, now(), myId)

    /** For Swift, which can't collect a Flow: [onChange] runs on the main thread for every state. */
    fun observe(onChange: (BridgeState) -> Unit) {
        scope.launch { state.collect { onChange(it) } }
    }

    fun start() {
        scope.launch {
            if (started) return@launch
            started = true
            val txt = mapOf("id" to myId, "name" to name, "platform" to platform.name.lowercase(), "v" to "$PROTOCOL_VERSION", "caps" to "")
            discovery.advertise(CONTROL_PORT, txt)
            transport.listen(CONTROL_PORT) { conn -> scope.launch { runSession(conn, expected = null, invite = null) } }
            discovery.browse { peers -> scope.launch { onDiscovered(peers.filter { it.id != myId }) } }
            clipboard.watch { text -> scope.launch { onLocalCopy(text) } }
            notifications.watch(notificationEvents)
            keyStore.trustedPeers().filter { it.host != null }.forEach { keepConnected(it.fingerprint) }
            refreshPaired()
        }
    }

    /** Host side: show a QR for [host] (this device's LAN address). */
    fun openPairing(host: String) {
        scope.launch { _state.update { it.copy(invite = PairingInvite(myId, host, CONTROL_PORT, newCode()), message = null) } }
    }

    fun closePairing() {
        scope.launch {
            pairingSession?.let { reject(it) }
            _state.update { it.copy(invite = null) }
        }
    }

    /**
     * Scanner side: [uri] is the QR text. [host], if set, replaces the QR's address (the user typed it because
     * the QR's address isn't reachable from here); the fingerprint and code still come from the QR.
     */
    fun pair(uri: String, host: String? = null) {
        scope.launch {
            val scanned = PairingInvite.parse(uri) ?: return@launch fail("That QR code isn't a Bridge pairing code")
            val invite = host?.trim()?.takeIf { it.isNotEmpty() }?.let { scanned.copy(host = it) } ?: scanned
            _state.update { it.copy(message = null) }
            val conn = connect(invite.host, invite.port) ?: return@launch
            runSession(conn, invite.fingerprint, invite)
            if (keyStore.trustedPeers().any { it.fingerprint == invite.fingerprint }) keepConnected(invite.fingerprint)
        }
    }

    /** Scanner side: the user saw the same code on both screens. */
    fun confirmPairing() {
        scope.launch {
            val s = pairingSession?.takeIf { it.invite != null && !it.confirmSent } ?: return@launch
            s.confirmSent = true
            send(s, PairConfirm(myId, s.invite!!.code))
            _state.update { it.copy(pairing = it.pairing?.copy(awaitingLocalConfirm = false)) }
        }
    }

    /** Either side: cancel the pairing in progress. */
    fun rejectPairing() {
        scope.launch { pairingSession?.let { reject(it) } }
    }

    /** Forget [fingerprint] and close its session (spec §5.1). */
    fun unpair(fingerprint: String) {
        scope.launch {
            dialers.remove(fingerprint)?.cancel()
            keyStore.removeTrustedPeer(fingerprint)
            online[fingerprint]?.let { drop(it, CLOSE_NORMAL, "unpaired") }
            refreshPaired()
        }
    }

    /** Scanner side: the user typed a new address for paired [fingerprint] (e.g. mDNS is blocked on this network). */
    fun setAddress(fingerprint: String, host: String) {
        scope.launch {
            val peer = keyStore.trustedPeers().find { it.fingerprint == fingerprint && it.host != null } ?: return@launch
            keyStore.saveTrustedPeer(peer.copy(host = host.trim()))
            refreshPaired()
            redial(fingerprint)
        }
    }

    /**
     * The device woke, the screen was unlocked or the network changed: dial offline peers now instead of
     * waiting out the backoff, and ping online ones, dropping any that don't answer within [PROBE_TIMEOUT]
     * (a socket from before sleep can look open for another minute).
     */
    fun reconnect() {
        scope.launch {
            online.values.toList().forEach { s ->
                val pongs = s.pongs
                send(s, Ping)
                scope.launch {
                    delay(PROBE_TIMEOUT)
                    if (s.pongs == pongs) drop(s, CLOSE_POLICY, "no answer after wake or network change")
                }
            }
            keyStore.trustedPeers().filter { it.host != null && it.fingerprint !in online }.forEach { redial(it.fingerprint) }
        }
    }

    private fun onDiscovered(peers: List<DiscoveredPeer>) {
        _state.update { it.copy(discovered = peers) }
        peers.forEach { p ->
            val at = (p.host ?: return@forEach) to p.port
            // A new address for a paired peer we can't reach: try it now rather than after the backoff.
            if (located.put(p.id, at) != at && p.id !in online && p.id in dialers) redial(p.id)
        }
    }

    private fun redial(fingerprint: String) {
        dialers.remove(fingerprint)?.cancel()
        keepConnected(fingerprint)
    }

    private fun keepConnected(fingerprint: String) {
        if (dialers[fingerprint]?.isActive == true) return
        dialers[fingerprint] = scope.launch {
            var backoff = 1.seconds
            while (true) {
                val peer = keyStore.trustedPeers().find { it.fingerprint == fingerprint } ?: break
                val stored = (peer.host ?: break) to peer.port
                // mDNS's current answer first; the last known address covers networks where mDNS is slow or blocked.
                for ((host, port) in listOfNotNull(located[fingerprint], stored).distinct()) {
                    val conn = connect(host, port) ?: continue
                    // Remember the address only once the pinned certificate proves it's the peer.
                    if (conn.peerFingerprint == fingerprint && (host to port) != stored) keyStore.saveTrustedPeer(peer.copy(host = host, port = port))
                    if (runSession(conn, fingerprint, invite = null)) backoff = 1.seconds
                    break
                }
                delay(backoff)
                backoff = minOf(backoff * 2, MAX_BACKOFF)
            }
        }
    }

    private suspend fun connect(host: String, port: Int): Connection? = suspendCancellableCoroutine { c ->
        transport.connect(host, port) { conn, error ->
            if (conn == null) fail("Can't reach $host:$port ($error)")
            c.resume(conn) { _, value, _ -> value?.close(CLOSE_NORMAL, "cancelled") }
        }
    }

    /** Runs one connection until it closes. Returns true if the peer was paired on it. */
    private suspend fun runSession(conn: Connection, expected: String?, invite: PairingInvite?): Boolean {
        if (expected != null && conn.peerFingerprint != expected) {
            conn.close(CLOSE_POLICY, "unexpected certificate")
            fail(if (invite != null) "The device that answered isn't the one in the QR code" else "A device at the paired address has a different identity")
            return false
        }
        val s = Session(conn, invite)
        var everPaired = false
        conn.start(object : ConnectionListener {
            override fun onText(text: String) {
                s.incoming.trySend(text)
            }

            override fun onBinary(bytes: ByteArray) {
                s.incoming.trySend(bytes)
            }

            override fun onClosed(reason: String) {
                s.incoming.close()
            }
        })
        send(s, Hello(myId, name, platform, appVersion, PROTOCOL_VERSION, emptyList()))
        try {
            coroutineScope {
                val heartbeat = launch {
                    while (true) {
                        delay(HEARTBEAT_INTERVAL)
                        if (s.peer == null) return@launch drop(s, CLOSE_POLICY, "no hello")
                        if (s.missedPongs >= MAX_MISSED_PONGS) return@launch drop(s, CLOSE_POLICY, "heartbeat timeout")
                        s.missedPongs++
                        send(s, Ping)
                    }
                }
                for (frame in s.incoming) {
                    if (frame is ByteArray) onBinary(s, frame) else handle(s, frame as String)
                    everPaired = everPaired || s.paired
                }
                heartbeat.cancel()
            }
        } finally {
            conn.close(CLOSE_NORMAL, "session ended")
            s.peer?.let { if (online[it.deviceId] === s) online.remove(it.deviceId) }
            if (pairingSession === s) endPairing(null)
            refreshPaired()
        }
        return everPaired
    }

    private fun handle(s: Session, text: String) {
        // The binary frame must come straight after its message (spec §4).
        if (s.awaitingBinary != null) {
            s.awaitingBinary = null
            send(s, ProtocolError("invalid_message", "expected a binary frame"))
        }
        val env = Codec.decode(text).getOrElse { e ->
            return if (e is UnsupportedTypeException) {
                send(s, ProtocolError("unsupported_type", "unknown type '${e.type}'"), e.id)
            } else {
                send(s, ProtocolError("invalid_message", "could not decode message"))
            }
        }
        if (env.v != PROTOCOL_VERSION) return drop(s, CLOSE_VERSION_MISMATCH, "protocol version ${env.v}")
        val m = env.message
        if (s.peer == null && m !is Hello && m !is Ping && m !is Pong) return send(s, ProtocolError("hello_required", "send hello first"), env.id)
        if (env.binaryLength != null) {
            s.awaitingBinary = env
            return
        }
        dispatch(s, env, null)
    }

    private fun onBinary(s: Session, bytes: ByteArray) {
        // Tolerant reader: the frame of a message we couldn't decode (e.g. a newer peer's type, already answered) is dropped.
        val env = s.awaitingBinary ?: return
        s.awaitingBinary = null
        if (bytes.size > MAX_ICON_BYTES) return send(s, ProtocolError("too_large", "binary frame over $MAX_ICON_BYTES bytes"), env.id)
        if (bytes.size.toLong() != env.binaryLength) return send(s, ProtocolError("invalid_message", "binaryLength mismatch"), env.id)
        dispatch(s, env, bytes)
    }

    /** [binary] is the frame that followed [env], if it announced one. */
    private fun dispatch(s: Session, env: Envelope, binary: ByteArray?) {
        val m = env.message
        // Exhaustive on purpose: a new message type must decide here whether it needs a paired session.
        when (m) {
            Ping -> send(s, Pong, replyTo = env.id)
            Pong -> {
                s.missedPongs = 0
                s.pongs++
            }
            is Hello -> onHello(s, m)
            is PairRequest -> onPairRequest(s, m, env.id)
            is PairConfirm -> onPairConfirm(s, m, env.id)
            is PairReject -> if (pairingSession === s) {
                endPairing("${s.peer?.name} declined pairing")
                rotateCode()
                drop(s, CLOSE_NORMAL, "pairing rejected")
            }
            is ProtocolError -> onPeerError(s, m)
            // Not "not_paired": that code makes a paired peer forget us.
            is ClipboardSet, ClipboardRequest -> if (!s.paired) {
                send(s, ProtocolError("unauthorized", "pair first"), env.id)
            } else if (m is ClipboardSet) {
                onClipboardSet(s, m, env)
            } else {
                // Only resend what the peer doesn't already have.
                clip.takeIf { it.text != null && it.origin != s.peer!!.deviceId }?.let { sendClip(s, it, env.id) }
            }
            is NotifPosted, is NotifDismissed, is NotifAction, is NotifReply -> if (!s.paired) {
                send(s, ProtocolError("unauthorized", "pair first"), env.id)
            } else {
                when (m) {
                    is NotifPosted -> notifications.show(m, binary)
                    is NotifDismissed -> notifications.remove(m.key)
                    is NotifAction -> notifications.action(m.key, m.actionIndex)
                    is NotifReply -> notifications.reply(m.key, m.replyText)
                    else -> {}
                }
            }
        }
    }

    /** Local notification events go to every paired peer online; nothing is queued for offline ones (spec §5.4). */
    private val notificationEvents = object : NotificationEvents {
        override fun posted(notification: NotifPosted, icon: ByteArray?) = broadcast(notification, icon?.takeIf { it.size <= MAX_ICON_BYTES })

        override fun dismissed(key: String) = broadcast(NotifDismissed(key))

        override fun action(key: String, index: Int) = broadcast(NotifAction(key, index))

        override fun reply(key: String, text: String) = broadcast(NotifReply(key, text))
    }

    private fun broadcast(m: Message, png: ByteArray? = null) {
        scope.launch { online.values.forEach { send(it, m, png = png) } }
    }

    private fun onLocalCopy(text: String) {
        // Same text as the shared clipboard: our own write seen again, or nothing new to send.
        if (text == clip.text) return
        if (text.encodeToByteArray().size > MAX_CLIPBOARD_BYTES) return fail("Copied text is over 1 MB, so it wasn't sent")
        // A real copy is always the newest event; never let a peer's clock running ahead make it lose.
        clip = Clip(text, maxOf(now(), clip.ts + 1), myId)
        online.values.forEach { sendClip(it, clip, null) }
    }

    private fun onClipboardSet(s: Session, m: ClipboardSet, env: Envelope) {
        val text = m.text
        if (m.mime != TEXT_MIME || text == null) return send(s, ProtocolError("unsupported_mime", "only text/plain"), env.id)
        if (text.encodeToByteArray().size > MAX_CLIPBOARD_BYTES) return send(s, ProtocolError("too_large", "clipboard text over 1 MB"), env.id)
        val incoming = Clip(text, env.ts, s.peer!!.deviceId)
        if (compareValuesBy(incoming, clip, { it.ts }, { it.origin }) <= 0) return
        val changed = text != clip.text
        clip = incoming
        if (changed) clipboard.write(text)
    }

    private fun sendClip(s: Session, c: Clip, replyTo: String?) {
        s.conn.send(Codec.encode(Envelope(newUlid(now()), c.ts, ClipboardSet(TEXT_MIME, c.text), replyTo)))
    }

    private fun onHello(s: Session, m: Hello) {
        if (s.peer != null) return
        if (m.protocolVersion != PROTOCOL_VERSION) return drop(s, CLOSE_VERSION_MISMATCH, "protocol version ${m.protocolVersion}")
        if (m.deviceId != s.conn.peerFingerprint) return drop(s, CLOSE_POLICY, "deviceId does not match certificate")
        s.peer = m
        val trusted = keyStore.trustedPeers().find { it.fingerprint == m.deviceId }
        when {
            trusted != null -> {
                if (trusted.name != m.name) keyStore.saveTrustedPeer(trusted.copy(name = m.name))
                markPaired(s)
            }
            s.invite != null -> {
                pairingSession?.let { reject(it) }
                pairingSession = s
                s.pairingCode = s.invite.code
                send(s, PairRequest(myId, s.invite.code))
                _state.update { it.copy(pairing = Pairing(m.name, s.invite.code, awaitingLocalConfirm = true), message = null) }
            }
            // Unpaired and nothing to pair with: refuse (spec §3).
            _state.value.invite == null -> {
                send(s, ProtocolError("not_paired", "this device is not paired"))
                drop(s, CLOSE_POLICY, "not paired")
            }
        }
    }

    /** Host side. The code proves the scanner saw this screen; the TLS certificate proves who it is. */
    private fun onPairRequest(s: Session, m: PairRequest, id: String) {
        val invite = _state.value.invite
        val ok = invite != null && !s.paired && s.invite == null && pairingSession == null &&
            m.fingerprint == s.conn.peerFingerprint && codesMatch(m.confirmCode, invite.code)
        if (!ok) {
            send(s, PairReject(myId, safeCode(m.confirmCode)), id)
            // One guess per code: rotating makes brute force pointless.
            rotateCode()
            fail("Refused a pairing attempt from ${s.peer?.name} with the wrong code")
            return drop(s, CLOSE_POLICY, "pairing refused")
        }
        pairingSession = s
        s.pairingCode = invite.code
        _state.update { it.copy(pairing = Pairing(s.peer!!.name, invite.code, awaitingLocalConfirm = false), message = null) }
    }

    private fun onPairConfirm(s: Session, m: PairConfirm, id: String) {
        val code = s.pairingCode
        val ok = pairingSession === s && code != null && m.fingerprint == s.conn.peerFingerprint && codesMatch(m.confirmCode, code) &&
            (s.invite == null || s.confirmSent)
        if (!ok) {
            send(s, PairReject(myId, safeCode(m.confirmCode)), id)
            if (pairingSession === s) endPairing("Pairing failed")
            rotateCode()
            return drop(s, CLOSE_POLICY, "unexpected pair.confirm")
        }
        val peer = s.peer!!
        keyStore.saveTrustedPeer(TrustedPeer(peer.deviceId, peer.name, s.invite?.host, s.invite?.port ?: 0))
        // Host acknowledges so the scanner stores trust only once the host has.
        if (s.invite == null) send(s, PairConfirm(myId, code), id)
        endPairing(null)
        _state.update { it.copy(invite = null) }
        markPaired(s)
    }

    private fun onPeerError(s: Session, m: ProtocolError) {
        // An older peer build doesn't know one of our message types: expected between versions, nothing the user can fix.
        if (m.code == "unsupported_type") return
        val peer = s.peer
        // Authentic: this connection is pinned to the peer we dialled. It forgot us, so forget it too.
        if (m.code == "not_paired" && peer != null && s.invite == null) {
            dialers.remove(peer.deviceId)?.cancel()
            keyStore.removeTrustedPeer(peer.deviceId)
            fail("${peer.name} no longer trusts this device; pair again")
            return drop(s, CLOSE_NORMAL, "not paired")
        }
        fail("${peer?.name ?: "Peer"}: ${m.message}")
    }

    private fun markPaired(s: Session) {
        s.paired = true
        val id = s.peer!!.deviceId
        online.put(id, s)?.takeIf { it !== s }?.let { drop(it, CLOSE_NORMAL, "replaced by a newer connection") }
        // Catch up on anything copied while we were apart; last-writer-wins sorts out which side keeps it.
        send(s, ClipboardRequest)
        _state.update { it.copy(message = null) }
        refreshPaired()
    }

    private fun reject(s: Session) {
        s.pairingCode?.let { send(s, PairReject(myId, it)) }
        endPairing(null)
        rotateCode()
        drop(s, CLOSE_NORMAL, "pairing cancelled")
    }

    private fun endPairing(message: String?) {
        pairingSession = null
        _state.update { it.copy(pairing = null, message = message ?: it.message) }
    }

    private fun rotateCode() = _state.update { s -> s.copy(invite = s.invite?.copy(code = newCode())) }

    private fun refreshPaired() = _state.update { s ->
        s.copy(paired = keyStore.trustedPeers().map { PeerStatus(it.fingerprint, it.name, it.fingerprint in online, it.host) })
    }

    private fun fail(message: String) = _state.update { it.copy(message = message) }

    private fun drop(s: Session, code: Int, reason: String) {
        s.conn.close(code, reason)
        s.incoming.close()
    }

    private fun send(s: Session, m: Message, replyTo: String? = null, png: ByteArray? = null) {
        val now = now()
        s.conn.send(Codec.encode(Envelope(newUlid(now), now, m, replyTo, png?.size?.toLong(), png?.let { PNG_MIME })))
        png?.let(s.conn::sendBinary)
    }
}

private fun now() = Clock.System.now().toEpochMilliseconds()

private fun newCode(): String {
    val n = secureRandomBytes(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
    return (n % 1_000_000).toString().padStart(6, '0')
}

private fun codesMatch(a: String, b: String): Boolean =
    a.length == b.length && a.indices.fold(0) { acc, i -> acc or (a[i].code xor b[i].code) } == 0

/** pair.reject echoes the code it refuses; never echo malformed input (schema: 6 digits). */
private fun safeCode(code: String) = code.takeIf { it.length == 6 && it.all(Char::isDigit) } ?: "000000"
