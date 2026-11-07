package bridge.port

/**
 * TLS 1.3 WebSocket control channel (spec §3). The platform does the TLS handshake with the
 * identity from [KeyStorePort], always presents its certificate (mutual TLS), accepts any peer
 * certificate, and reports its fingerprint; core decides whether to trust it.
 */
interface TransportPort {
    /** Starts the server on [port]. Default no-op for platforms that only dial out. */
    fun listen(port: Int, onConnection: (Connection) -> Unit) {}

    /** Opens `wss://host:port/`. Calls back once: a connection, or null and the failure reason. */
    fun connect(host: String, port: Int, callback: (Connection?, String?) -> Unit)
}

interface Connection {
    /** SHA-256 of the peer's DER certificate, lowercase hex. */
    val peerFingerprint: String

    /** Starts delivering frames to [listener], including any that arrived before this call. */
    fun start(listener: ConnectionListener)

    fun send(text: String)

    /** Sends a binary frame; it must arrive right after the text frame sent before it (spec §4). */
    fun sendBinary(bytes: ByteArray)

    /** Sends a WebSocket close frame with [code] (e.g. 4001 for a protocol-version mismatch) and drops the socket. */
    fun close(code: Int, reason: String)
}

/** Called from any thread. */
interface ConnectionListener {
    fun onText(text: String)

    /** A binary frame, in order with the text frames. */
    fun onBinary(bytes: ByteArray) {}

    /** Called once, whether the peer or we closed it. */
    fun onClosed(reason: String)
}
