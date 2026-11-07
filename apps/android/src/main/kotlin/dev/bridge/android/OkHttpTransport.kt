package dev.bridge.android

import bridge.identity.fingerprint
import bridge.port.Connection
import bridge.port.ConnectionListener
import bridge.port.TransportPort
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Dials `wss://host:port/` with OkHttp over the platform TLS stack (Conscrypt, TLS 1.3 only),
 * presenting the Keystore identity as the client certificate (mutual TLS).
 */
class OkHttpTransport(private val keyStore: AndroidKeyStorePort) : TransportPort {
    private val keyManager = IdentityKeyManager(keyStore)

    private val baseClient = OkHttpClient.Builder()
        // Peers are addressed by IP with a CN=Bridge certificate; identity is the pinned fingerprint.
        .hostnameVerifier { _, _ -> true }
        .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS).tlsVersions(TlsVersion.TLS_1_3).build()))
        // Core's 15 s heartbeat decides when a quiet socket is dead.
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    override fun connect(host: String, port: Int, callback: (Connection?, String?) -> Unit) {
        val connection = OkConnection(callback)
        // One trust manager per connection, because it is where we see the peer's certificate:
        // OkHttp's Handshake.peerCertificates is "cleaned" against trusted CAs and comes back empty.
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("client only")

            // Self-signed peers have no CA to check. Core compares this fingerprint with the
            // paired/QR one as soon as the socket opens, before sending anything.
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                connection.peerCertificate = chain.firstOrNull()?.encoded
            }

            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        }
        val tls = SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), arrayOf(trustManager), null) }
        val client = baseClient.newBuilder().sslSocketFactory(tls.socketFactory, trustManager).build()
        val authority = if (':' in host) "[$host]:$port" else "$host:$port"
        client.newWebSocket(Request.Builder().url("wss://$authority/").build(), connection)
    }
}

private class OkConnection(private var callback: ((Connection?, String?) -> Unit)?) : WebSocketListener(), Connection {
    private val lock = Any()
    private lateinit var socket: WebSocket

    @Volatile
    var peerCertificate: ByteArray? = null
    private var listener: ConnectionListener? = null
    private val pending = mutableListOf<String>()
    private var closedReason: String? = null
    override lateinit var peerFingerprint: String

    override fun onOpen(webSocket: WebSocket, response: Response) {
        val leaf = peerCertificate
        if (leaf == null) {
            webSocket.cancel()
            return report(null, "peer presented no certificate")
        }
        socket = webSocket
        peerFingerprint = fingerprint(leaf)
        report(this, null)
    }

    override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
        listener?.onText(text) ?: pending.add(text)
        Unit
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, null)
        closed("closed by peer: $code $reason")
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed("closed: $code $reason")

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        report(null, t.message ?: t.toString())
        closed(t.toString())
    }

    /** Core's connect callback must fire exactly once; later failures are closes. */
    private fun report(connection: Connection?, error: String?) {
        val cb = synchronized(lock) { callback.also { callback = null } } ?: return
        cb(connection, error)
    }

    override fun start(listener: ConnectionListener) = synchronized(lock) {
        this.listener = listener
        pending.forEach(listener::onText)
        pending.clear()
        closedReason?.let(listener::onClosed)
        Unit
    }

    override fun send(text: String) {
        socket.send(text)
    }

    override fun close(code: Int, reason: String) {
        // WebSocket close reasons are capped at 123 bytes.
        socket.close(code, reason.take(100))
        closed(reason)
    }

    private fun closed(reason: String) = synchronized(lock) {
        if (closedReason == null) {
            closedReason = reason
            listener?.onClosed(reason)
        }
    }
}
