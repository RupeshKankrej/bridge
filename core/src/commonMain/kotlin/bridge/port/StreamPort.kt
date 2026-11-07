package bridge.port

/**
 * TLS 1.3 byte streams for the file API (spec §4, ADR 0007); core speaks HTTP/1.1 over them. The
 * platform presents the identity from [KeyStorePort] on both sides, accepts any peer certificate and
 * reports its fingerprint; core decides whether to trust it.
 */
interface StreamPort {
    /**
     * Listens on [port]. With [requireClientCertificate] (53319, paired Bridge peers) the handshake fails
     * without one; otherwise (53317, LocalSend) the server doesn't ask for one.
     */
    fun listen(port: Int, requireClientCertificate: Boolean, onStream: (ByteStream) -> Unit)

    /**
     * Connects, presenting our certificate. Calls back once: a stream, or null and the failure reason.
     * Not named `connect`: that would clash with TransportPort.connect's Objective-C selector.
     */
    fun dial(host: String, port: Int, callback: (ByteStream?, String?) -> Unit)
}

/** One TLS connection. Callbacks may run on any thread; each call gets exactly one callback. */
interface ByteStream {
    /** SHA-256 of the peer's DER certificate, lowercase hex; null when a client presented none. */
    val peerFingerprint: String?

    /** The peer's IP address. */
    val remoteHost: String

    /** Delivers 1..[max] bytes, an empty array at end of stream, or null and the failure reason. */
    fun read(max: Int, callback: (ByteArray?, String?) -> Unit)

    /** Calls back with null once [data] is handed to the OS, or with the failure reason. */
    fun write(data: ByteArray, callback: (String?) -> Unit)

    fun close()
}

/** LocalSend multicast discovery: UDP 224.0.0.167:53317 (spec §5.3). */
interface MulticastPort {
    /** Joins the group and calls [onDatagram] with each datagram and the sender's IP address. */
    fun join(onDatagram: (ByteArray, String) -> Unit)

    fun send(data: ByteArray)
}
