package bridge.port

/**
 * The device's P-256 identity key and the list of trusted peers, kept in the OS keystore
 * (Keychain on macOS; Android Keystore for the key, app-private storage for the trust list).
 * The private key never leaves the keystore; core only asks it to sign.
 */
interface KeyStorePort {
    /** The certificate stored by [saveCertificate] (DER), or null on first launch. */
    fun loadCertificate(): ByteArray?

    /** Creates the P-256 identity key, replacing any previous one; returns its DER SubjectPublicKeyInfo. */
    @Throws(Exception::class)
    fun generateKey(): ByteArray

    /** ECDSA-with-SHA256 signature over [data] with the identity key, DER-encoded. */
    @Throws(Exception::class)
    fun sign(data: ByteArray): ByteArray

    /** Stores the self-signed certificate so the platform TLS stack presents it with the identity key. */
    @Throws(Exception::class)
    fun saveCertificate(certificate: ByteArray)

    fun trustedPeers(): List<TrustedPeer>

    /** Adds or replaces the peer with the same fingerprint. */
    fun saveTrustedPeer(peer: TrustedPeer)

    fun removeTrustedPeer(fingerprint: String)
}

/** A paired device. [host] is set only on the side that dialled it, which is the side that reconnects. */
data class TrustedPeer(
    val fingerprint: String,
    val name: String,
    val host: String? = null,
    val port: Int = 0,
)
