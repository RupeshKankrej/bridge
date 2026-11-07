package dev.bridge.android

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import bridge.identity.fingerprint
import bridge.port.ByteStream
import bridge.port.FileSink
import bridge.port.FileSource
import bridge.port.FileStorePort
import bridge.port.MulticastPort
import bridge.port.OutgoingFile
import bridge.port.StreamPort
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

private const val TAG = "BridgeFiles"

/** Blocking socket calls, turned into StreamPort's callbacks. */
private val io = Executors.newCachedThreadPool { r -> Thread(r, "bridge-files").apply { isDaemon = true } }

/**
 * StreamPort on platform TLS 1.3 sockets (Conscrypt). Server and client present the Keystore identity;
 * any peer certificate is accepted and core pins fingerprints.
 */
class AndroidStreams(keyStore: AndroidKeyStorePort) : StreamPort {
    private val tls: SSLContext by lazy {
        val anyone = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        }
        SSLContext.getInstance("TLS").apply { init(arrayOf(IdentityKeyManager(keyStore)), arrayOf(anyone), null) }
    }

    override fun listen(port: Int, requireClientCertificate: Boolean, onStream: (ByteStream) -> Unit) {
        val server = try {
            (tls.serverSocketFactory.createServerSocket(port) as SSLServerSocket).apply {
                enabledProtocols = arrayOf("TLSv1.3")
                // Same as the Mac: 53319 requires a certificate, 53317 never asks (ADR 0007).
                needClientAuth = requireClientCertificate
            }
        } catch (e: IOException) {
            Log.e(TAG, "can't listen on $port", e)
            return
        }
        thread(name = "bridge-files-accept-$port", isDaemon = true) {
            while (true) {
                val socket = try {
                    server.accept() as SSLSocket
                } catch (e: IOException) {
                    Log.e(TAG, "accept on $port failed", e)
                    break
                }
                io.execute {
                    try {
                        socket.startHandshake()
                        onStream(SocketStream(socket))
                    } catch (e: IOException) {
                        // Includes a stranger without a certificate on the paired port.
                        Log.d(TAG, "file connection failed before ready: ${e.message}")
                        runCatching { socket.close() }
                    }
                }
            }
        }
    }

    override fun dial(host: String, port: Int, callback: (ByteStream?, String?) -> Unit) = io.execute {
        try {
            val socket = (tls.socketFactory.createSocket() as SSLSocket).apply {
                enabledProtocols = arrayOf("TLSv1.3")
                connect(InetSocketAddress(host, port), 10_000)
                startHandshake()
            }
            callback(SocketStream(socket), null)
        } catch (e: IOException) {
            callback(null, e.message ?: e.toString())
        }
    }
}

private class SocketStream(private val socket: SSLSocket) : ByteStream {
    override val peerFingerprint: String? = runCatching { fingerprint(socket.session.peerCertificates.first().encoded) }.getOrNull()
    override val remoteHost: String = socket.inetAddress.hostAddress ?: ""

    override fun read(max: Int, callback: (ByteArray?, String?) -> Unit) = io.execute {
        try {
            val buf = ByteArray(max)
            val n = socket.inputStream.read(buf)
            callback(if (n < 0) ByteArray(0) else buf.copyOf(n), null)
        } catch (e: IOException) {
            callback(null, e.message ?: "read failed")
        }
    }

    override fun write(data: ByteArray, callback: (String?) -> Unit) = io.execute {
        try {
            socket.outputStream.write(data)
            callback(null)
        } catch (e: IOException) {
            callback(e.message ?: "write failed")
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}

/**
 * LocalSend multicast. Android Wi-Fi drops multicast unless an app holds a MulticastLock; we hold it while
 * the service runs (it costs some battery), because LocalSend devices announce whenever they start.
 */
class AndroidMulticast(context: Context) : MulticastPort {
    private val group = InetSocketAddress(InetAddress.getByName("224.0.0.167"), 53317)
    private val lock = context.getSystemService(WifiManager::class.java).createMulticastLock("bridge-localsend").apply { setReferenceCounted(false) }
    private var socket: MulticastSocket? = null

    override fun join(onDatagram: (ByteArray, String) -> Unit) {
        lock.acquire()
        val socket = try {
            MulticastSocket(53317).also { s ->
                NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && it.supportsMulticast() && !it.isLoopback }
                    .forEach { runCatching { s.joinGroup(group, it) } }
            }
        } catch (e: IOException) {
            Log.e(TAG, "can't join LocalSend multicast", e)
            return
        }
        this.socket = socket
        thread(name = "bridge-multicast", isDaemon = true) {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: IOException) {
                    Log.e(TAG, "multicast receive failed", e)
                    break
                }
                onDatagram(packet.data.copyOf(packet.length), packet.address.hostAddress ?: continue)
            }
        }
    }

    override fun send(data: ByteArray) = io.execute {
        runCatching { socket?.send(DatagramPacket(data, data.size, group)) }.onFailure { Log.d(TAG, "multicast send failed: ${it.message}") }
    }
}

/**
 * FileStorePort: `Download/Bridge` through MediaStore, which needs no storage permission on Android 10+.
 * Entries stay IS_PENDING (invisible to other apps) until complete; MediaStore picks a free name on clashes.
 */
class MediaStoreFileStore(private val resolver: ContentResolver) : FileStorePort {
    override fun create(path: String): FileSink {
        val name = path.substringAfterLast('/')
        val folder = listOf(Environment.DIRECTORY_DOWNLOADS, "Bridge", path.substringBeforeLast('/', "")).filter { it.isNotEmpty() }.joinToString("/")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, "$folder/")
            put(MediaStore.Downloads.IS_PENDING, 1)
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())?.let {
                put(MediaStore.Downloads.MIME_TYPE, it)
            }
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("MediaStore refused $path")
        val out = resolver.openOutputStream(uri) ?: throw IOException("can't write $path")
        return MediaStoreSink(resolver, uri, out)
    }
}

private class MediaStoreSink(private val resolver: ContentResolver, private val uri: Uri, private val out: OutputStream) : FileSink {
    override fun write(data: ByteArray) = out.write(data)

    override fun close(keep: Boolean) {
        runCatching { out.close() }
        if (keep) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } else {
            resolver.delete(uri, null, null)
        }
    }
}

/**
 * A picked or shared file read through its content URI. [held] keeps a share-sheet file readable after
 * the sharing app's temporary permission ends with our activity: an open descriptor stays valid.
 */
class UriFile(
    private val resolver: ContentResolver,
    private val uri: Uri,
    override val path: String,
    override val size: Long,
    override val mime: String,
    private val held: ParcelFileDescriptor? = null,
) : OutgoingFile {
    override fun open(offset: Long): FileSource {
        // shortcut: a held descriptor is closed only when this object is garbage-collected; fine for a handful of shared files.
        val pfd = held?.dup() ?: resolver.openFileDescriptor(uri, "r") ?: throw IOException("can't open $path")
        val stream = FileInputStream(pfd.fileDescriptor)
        // Seek for resume. Fails for pipes (some apps share non-seekable streams); then only offset 0 works.
        if (offset > 0) stream.channel.position(offset)
        return object : FileSource {
            override fun read(max: Int): ByteArray {
                val buf = ByteArray(max)
                val n = stream.read(buf)
                return if (n < 0) ByteArray(0) else buf.copyOf(n)
            }

            override fun close() {
                runCatching { stream.close() }
                runCatching { pfd.close() }
            }
        }
    }

    companion object {
        /** A single document (SAF pick or share). Null if its size is unknown, since LocalSend needs it up front. */
        fun of(resolver: ContentResolver, uri: Uri, hold: Boolean): UriFile? {
            var name: String? = null
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            val pfd = if (hold) runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull() else null
            if (size < 0) size = pfd?.statSize ?: -1
            if (size < 0) return null
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            return UriFile(resolver, uri, name ?: uri.lastPathSegment ?: "file", size, mime, pfd)
        }

        /** Every file under a picked folder, keeping "Folder/sub/name" paths; hidden files are skipped. */
        fun ofTree(resolver: ContentResolver, tree: Uri): List<UriFile> {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val rootName = resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Folder"
            val out = mutableListOf<UriFile>()
            fun walk(docId: String, prefix: String) {
                val columns = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                )
                resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), columns, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        if (name.startsWith(".")) continue
                        val mime = c.getString(2) ?: "application/octet-stream"
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            walk(id, "$prefix$name/")
                        } else if (!c.isNull(3)) {
                            out += UriFile(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id), "$prefix$name", c.getLong(3), mime)
                        }
                    }
                }
            }
            walk(rootId, "$rootName/")
            return out
        }
    }
}
