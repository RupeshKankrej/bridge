package bridge.interop

import bridge.identity.fingerprint
import bridge.port.FileSink
import bridge.port.FileSource
import bridge.port.FileStorePort
import bridge.port.KeyStorePort
import bridge.port.MulticastPort
import bridge.port.OutgoingFile
import bridge.port.ByteStream
import bridge.port.StreamPort
import bridge.port.TrustedPeer
import java.io.File
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/** JDK stand-ins for the platform ports, for the interop test only (the apps have their own). */
class JvmKeyStore : KeyStorePort {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private var certificate: ByteArray? = null

    override fun loadCertificate() = certificate

    override fun generateKey(): ByteArray = keys.public.encoded

    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(data); sign() }

    override fun saveCertificate(certificate: ByteArray) {
        this.certificate = certificate
    }

    override fun trustedPeers() = emptyList<TrustedPeer>()

    override fun saveTrustedPeer(peer: TrustedPeer) = Unit

    override fun removeTrustedPeer(fingerprint: String) = Unit

    /** TLS 1.3 presenting our certificate; trusts any peer, like the apps (core pins fingerprints). */
    fun sslContext(): SSLContext {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(certificate!!.inputStream())
        val ks = KeyStore.getInstance("PKCS12").apply { load(null); setKeyEntry("id", keys.private, CharArray(0), arrayOf(cert)) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, CharArray(0)) }
        val anyone = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        }
        return SSLContext.getInstance("TLSv1.3").apply { init(kmf.keyManagers, arrayOf(anyone), null) }
    }
}

private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

class JvmStreams(private val ssl: SSLContext) : StreamPort {
    override fun listen(port: Int, requireClientCertificate: Boolean, onStream: (ByteStream) -> Unit) {
        val server = ssl.serverSocketFactory.createServerSocket(port) as SSLServerSocket
        server.enabledProtocols = arrayOf("TLSv1.3")
        server.needClientAuth = requireClientCertificate
        thread(isDaemon = true) { while (true) onStream(SocketStream(server.accept() as SSLSocket)) }
    }

    override fun dial(host: String, port: Int, callback: (ByteStream?, String?) -> Unit) {
        pool.execute {
            val s = runCatching { (ssl.socketFactory.createSocket() as SSLSocket).apply { connect(InetSocketAddress(host, port), 10_000); enabledProtocols = arrayOf("TLSv1.3") } }
            s.fold({ callback(SocketStream(it), null) }, { callback(null, it.message) })
        }
    }
}

private class SocketStream(private val socket: SSLSocket) : ByteStream {
    override val peerFingerprint: String? by lazy {
        runCatching { fingerprint(socket.session.peerCertificates.first().encoded) }.getOrNull()
    }
    override val remoteHost: String = socket.inetAddress.hostAddress

    override fun read(max: Int, callback: (ByteArray?, String?) -> Unit) = pool.execute {
        runCatching {
            val buf = ByteArray(max)
            val n = socket.inputStream.read(buf)
            if (n < 0) ByteArray(0) else buf.copyOf(n)
        }.fold({ callback(it, null) }, { callback(null, it.message ?: "read failed") })
    }

    override fun write(data: ByteArray, callback: (String?) -> Unit) = pool.execute {
        callback(runCatching { socket.outputStream.write(data) }.exceptionOrNull()?.let { it.message ?: "write failed" })
    }

    override fun close() = runCatching { socket.close() }.let { }
}

class JvmMulticast : MulticastPort {
    private val group = InetSocketAddress(InetAddress.getByName("224.0.0.167"), 53317)
    private val socket = MulticastSocket(53317)

    override fun join(onDatagram: (ByteArray, String) -> Unit) {
        NetworkInterface.networkInterfaces().filter { it.isUp && it.supportsMulticast() && !it.isLoopback }.forEach {
            runCatching { socket.joinGroup(group, it) }
        }
        thread(isDaemon = true) {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val p = DatagramPacket(buf, buf.size)
                socket.receive(p)
                onDatagram(p.data.copyOf(p.length), p.address.hostAddress)
            }
        }
    }

    override fun send(data: ByteArray) = socket.send(DatagramPacket(data, data.size, group))
}

class DirStore(private val dir: File) : FileStorePort {
    override fun create(path: String): FileSink {
        val target = File(dir, path).also { it.parentFile.mkdirs() }
        val part = File(target.path + ".part")
        val out = part.outputStream()
        return object : FileSink {
            override fun write(data: ByteArray) = out.write(data)

            override fun close(keep: Boolean) {
                out.close()
                if (keep) part.renameTo(target) else part.delete()
            }
        }
    }
}

class DiskFile(private val file: File) : OutgoingFile {
    override val path: String = file.name
    override val size get() = file.length()
    override val mime = "application/octet-stream"

    override fun open(offset: Long): FileSource {
        val raf = RandomAccessFile(file, "r").apply { seek(offset) }
        return object : FileSource {
            override fun read(max: Int): ByteArray {
                val buf = ByteArray(max)
                val n = raf.read(buf)
                return if (n < 0) ByteArray(0) else buf.copyOf(n)
            }

            override fun close() = raf.close()
        }
    }
}
