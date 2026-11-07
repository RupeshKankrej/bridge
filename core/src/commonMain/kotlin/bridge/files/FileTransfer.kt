package bridge.files

import bridge.identity.Identity
import bridge.identity.secureRandomBytes
import bridge.port.FileSink
import bridge.port.FileStorePort
import bridge.port.KeyStorePort
import bridge.port.MulticastPort
import bridge.port.OutgoingFile
import bridge.port.ByteStream
import bridge.port.StreamPort
import bridge.protocol.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** How long an unpaired sender's offer waits for Accept / Decline. */
internal val OFFER_TIMEOUT = 2.minutes

/** An inbound session with nothing uploading for this long ends; covers a sender that vanished mid-resume. */
internal val SESSION_IDLE = 60.seconds
internal val RETRY_DELAY = 2.seconds

/** Resume attempts in a row without progress before an upload fails: 30 × 2 s ≈ a minute of Wi-Fi drop. */
internal const val MAX_RETRIES = 30
private const val MAX_JSON = 8 * 1024 * 1024
private val PUBLISH_EVERY = 250.milliseconds

data class FilesState(
    /** LocalSend devices on the LAN, Bridge peers included (they answer multicast too). */
    val nearby: List<LocalSendPeer> = emptyList(),
    val transfers: List<Transfer> = emptyList(),
    /** An unpaired sender waiting for [FileTransfer.respond]. */
    val offer: Offer? = null,
)

data class LocalSendPeer(val fingerprint: String, val alias: String, val deviceType: String?, val host: String, val port: Int)

data class Transfer(
    val id: String,
    val peerName: String,
    val incoming: Boolean,
    val files: List<TransferFile>,
    val status: TransferStatus,
    val bytesPerSecond: Long = 0,
    /** Why it failed, worded for the user; the last failure if several files failed. */
    val error: String? = null,
)

data class TransferFile(val name: String, val size: Long, val done: Long = 0, val status: TransferStatus = TransferStatus.WAITING)

enum class TransferStatus { WAITING, RUNNING, PAUSED, DONE, FAILED, CANCELLED, DECLINED }

data class Offer(val id: String, val senderName: String, val files: List<TransferFile>)

/**
 * LocalSend v2.1 over HTTPS (spec §4, §5.3, ADR 0007): the receiving server, the sending client and
 * multicast discovery. Port 53317 is plain LocalSend: every offer gets an Accept / Decline prompt. Port
 * 53319 serves the same API but requires a client certificate, and only paired peers get in; they
 * auto-accept and may resume an upload after a drop. All state lives on [scope]'s single thread; file
 * I/O runs on [io].
 */
class FileTransfer internal constructor(
    private val name: String,
    private val platform: Platform,
    private val deviceModel: String?,
    private val keyStore: KeyStorePort,
    private val streams: StreamPort,
    private val multicast: MulticastPort,
    private val store: FileStorePort,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val port: Int = LOCALSEND_PORT,
    private val pairedPort: Int = PAIRED_FILES_PORT,
) {
    constructor(
        name: String,
        platform: Platform,
        deviceModel: String?,
        keyStore: KeyStorePort,
        streams: StreamPort,
        multicast: MulticastPort,
        store: FileStorePort,
    ) : this(
        name, platform, deviceModel, keyStore, streams, multicast, store,
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)), Dispatchers.IO,
    )

    private val myId = Identity.loadOrCreate(keyStore).fingerprint
    private val _state = MutableStateFlow(FilesState())
    val state: StateFlow<FilesState> = _state.asStateFlow()

    /** For Swift: [onChange] runs on the main thread for every state. */
    fun observe(onChange: (FilesState) -> Unit) {
        scope.launch(Dispatchers.Main) { state.collect { onChange(it) } }
    }

    fun start() {
        scope.launch {
            streams.listen(port, requireClientCertificate = false) { s -> scope.launch { serve(s, paired = false) } }
            streams.listen(pairedPort, requireClientCertificate = true) { s -> scope.launch { serve(s, paired = true) } }
            multicast.join { data, host -> scope.launch { onDatagram(data, host) } }
            announce()
        }
    }

    /** Asks LocalSend devices to answer again, e.g. from a refresh button. */
    fun refresh() {
        scope.launch { announce() }
    }

    fun send(peer: LocalSendPeer, files: List<OutgoingFile>) {
        scope.launch {
            val live = track(peer.alias, incoming = false, files.map { TransferFile(it.path, it.size) })
            val job = scope.launch(start = CoroutineStart.LAZY) { sendAll(live, peer, files) }
            outgoing[live.id] = job
            job.invokeOnCompletion { scope.launch { outgoing.remove(live.id) } }
            job.start()
        }
    }

    /** The user's answer to [FilesState.offer]. */
    fun respond(offerId: String, accept: Boolean) {
        scope.launch { if (_state.value.offer?.id == offerId) pendingOffer?.complete(accept) }
    }

    fun cancel(transferId: String) {
        scope.launch {
            outgoing[transferId]?.cancel()
            inbound?.takeIf { it.live.id == transferId }?.let { end(it, TransferStatus.CANCELLED) }
        }
    }

    // ---- progress -------------------------------------------------------------------------------

    private class Live(val id: String, val peerName: String, val incoming: Boolean, val files: MutableList<TransferFile>) {
        var status = TransferStatus.WAITING
        var speed = 0L
        var error: String? = null
        var mark = TimeSource.Monotonic.markNow()
        var markBytes = 0L
        val total get() = files.sumOf { it.done }
    }

    private val outgoing = mutableMapOf<String, Job>()

    private fun track(peerName: String, incoming: Boolean, files: List<TransferFile>): Live {
        val l = Live(newId(), peerName, incoming, files.toMutableList())
        _state.update { it.copy(transfers = it.transfers + snapshot(l)) }
        return l
    }

    private fun snapshot(l: Live) = Transfer(l.id, l.peerName, l.incoming, l.files.toList(), l.status, l.speed, l.error)

    private fun publish(l: Live) = _state.update { s -> s.copy(transfers = s.transfers.map { if (it.id == l.id) snapshot(l) else it }) }

    private fun setFile(l: Live, i: Int, status: TransferStatus, done: Long = l.files[i].done) {
        l.files[i] = l.files[i].copy(status = status, done = done)
        if (status == TransferStatus.RUNNING) l.status = status
        publish(l)
    }

    /** Throttled: a 2 GB file would otherwise publish 8000 states. */
    private fun progress(l: Live, i: Int, done: Long) {
        l.files[i] = l.files[i].copy(done = done)
        val elapsed = l.mark.elapsedNow()
        if (elapsed < PUBLISH_EVERY) return
        val total = l.total
        l.speed = (total - l.markBytes) * 1000 / elapsed.inWholeMilliseconds.coerceAtLeast(1)
        l.mark = TimeSource.Monotonic.markNow()
        l.markBytes = total
        publish(l)
    }

    private fun finish(l: Live, status: TransferStatus, error: String? = null) {
        l.status = status
        if (error != null) l.error = error
        l.speed = 0
        l.files.indices.forEach { i ->
            if (l.files[i].status !in FINAL) l.files[i] = l.files[i].copy(status = if (status == TransferStatus.DONE) TransferStatus.FAILED else status)
        }
        publish(l)
    }

    // ---- discovery ------------------------------------------------------------------------------

    private fun info(announce: Boolean? = null, network: Boolean = announce != null) = DeviceInfo(
        alias = name,
        deviceModel = deviceModel,
        deviceType = if (platform == Platform.MACOS) "desktop" else "mobile",
        fingerprint = myId,
        port = port.takeIf { network },
        protocol = "https".takeIf { network },
        download = false,
        announce = announce,
        announcement = announce,
    )

    private fun announce() = multicast.send(localSendJson.encodeToString(DeviceInfo.serializer(), info(announce = true)).encodeToByteArray())

    private fun addPeer(host: String, dto: DeviceInfo): LocalSendPeer? {
        if (dto.fingerprint == myId || dto.fingerprint.isEmpty()) return null
        // shortcut: HTTP-mode LocalSend devices (encryption off) are left out because StreamPort is TLS only; add a plain-TCP port if users ask.
        if (dto.protocol == "http") return null
        val peer = LocalSendPeer(dto.fingerprint, dto.alias, dto.deviceType, host, dto.port ?: LOCALSEND_PORT)
        // shortcut: peers never expire; the list is only as fresh as the last announce.
        _state.update { s -> s.copy(nearby = s.nearby.filter { it.fingerprint != peer.fingerprint } + peer) }
        return peer
    }

    private suspend fun onDatagram(data: ByteArray, host: String) {
        val dto = decode(DeviceInfo.serializer(), data) ?: return
        val peer = addPeer(host, dto)
        if (dto.announce != true && dto.announcement != true) return
        // LocalSend's preferred answer is POST /register; UDP is the fallback.
        val registered = peer != null && try {
            request(peer, "POST", "$API/register", json(DeviceInfo.serializer(), info(network = true))).first.status == 200
        } catch (e: StreamException) {
            false
        }
        if (!registered) multicast.send(json(DeviceInfo.serializer(), info(announce = false)))
    }

    // ---- server ---------------------------------------------------------------------------------

    private class Inbound(val id: String, val host: String, val sender: String?, val trusted: Boolean, val files: Map<String, InFile>, val live: Live) {
        var idle: Job? = null
        var ended = false
    }

    private class InFile(val index: Int, val size: Long, val path: String, val token: String) {
        var sink: FileSink? = null
        var received = 0L
        var stream: ByteStream? = null
        var finished = false
    }

    private var inbound: Inbound? = null
    private var pendingOffer: CompletableDeferred<Boolean>? = null

    private suspend fun serve(stream: ByteStream, paired: Boolean) {
        val http = HttpStream(stream)
        // Only the paired port asks for a certificate, so only there does it mean anything.
        val trusted = paired && isTrusted(stream.peerFingerprint)
        try {
            while (true) {
                val head = http.readHead() ?: break
                if (paired && !trusted) {
                    refuse(http, 403)
                    break
                }
                if (!route(http, head, trusted)) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Dropped or malformed connection; upload() has already settled its file. Kotlin/Native would
            // terminate the app on an uncaught exception, so nothing from a remote peer may escape.
        } finally {
            stream.close()
        }
    }

    /** Returns whether to keep the connection open for another request. */
    private suspend fun route(http: HttpStream, head: HttpHead, trusted: Boolean): Boolean {
        val keepAlive = !head.headers["connection"].equals("close", ignoreCase = true)
        val ok = when ("${head.method} ${head.path}") {
            "GET $API/info" -> http.respond(200, json(DeviceInfo.serializer(), info())).let { true }
            "POST $API/register" -> register(http, head)
            "POST $API/prepare-upload" -> prepareUpload(http, head, trusted)
            "POST $API/upload" -> upload(http, head)
            "HEAD $API/upload" -> uploadOffset(http, head)
            "POST $API/cancel" -> cancelRequest(http, head)
            else -> refuse(http, 404)
        }
        return ok && keepAlive
    }

    /** Error replies don't read the request body, so the connection can't be reused. */
    private suspend fun refuse(http: HttpStream, status: Int, headers: Map<String, String> = emptyMap()): Boolean {
        http.respond(status, headers = headers, close = true)
        return false
    }

    private suspend fun register(http: HttpStream, head: HttpHead): Boolean {
        val dto = decode(DeviceInfo.serializer(), http.readBytes(head, MAX_JSON)) ?: return refuse(http, 400)
        addPeer(http.stream.remoteHost, dto)
        http.respond(200, json(DeviceInfo.serializer(), info()))
        return true
    }

    private suspend fun prepareUpload(http: HttpStream, head: HttpHead, trusted: Boolean): Boolean {
        val req = decode(PrepareUpload.serializer(), http.readBytes(head, MAX_JSON)) ?: return refuse(http, 400)
        val files = req.files.values.map { it to (safePath(it.fileName) ?: return refuse(http, 400)) }
        if (files.isEmpty() || files.any { it.first.size < 0 }) return refuse(http, 400)
        if (inbound != null || pendingOffer != null) return refuse(http, 409)
        val sender = http.stream.peerFingerprint.takeIf { trusted }
        val list = files.map { (f, path) -> TransferFile(path, f.size) }
        if (!trusted) {
            val answer = CompletableDeferred<Boolean>()
            pendingOffer = answer
            _state.update { it.copy(offer = Offer(newId(), req.info.alias, list)) }
            val accepted = try {
                withTimeoutOrNull(OFFER_TIMEOUT) { answer.await() } ?: false
            } finally {
                pendingOffer = null
                _state.update { it.copy(offer = null) }
            }
            if (!accepted) return refuse(http, 403)
        }
        val inFiles = files.mapIndexed { i, (f, path) -> f.id to InFile(i, f.size, path, newId()) }.toMap()
        val s = Inbound(newId(), http.stream.remoteHost, sender, trusted, inFiles, track(req.info.alias, incoming = true, list))
        inbound = s
        idleTimeout(s)
        val body = PrepareUploadResponse(s.id, inFiles.mapValues { it.value.token })
        http.respond(200, json(PrepareUploadResponse.serializer(), body), if (trusted) mapOf(RESUME_HEADER to "1") else emptyMap())
        return true
    }

    /** The session and file named by the query, or null if the request may not touch them. */
    private fun target(http: HttpStream, head: HttpHead): Pair<Inbound, InFile>? {
        val q = head.query
        val s = inbound?.takeIf { it.id == q["sessionId"] } ?: return null
        val f = s.files[q["fileId"]]?.takeIf { tokensMatch(it.token, q["token"] ?: "") } ?: return null
        // A paired sender is pinned by its certificate, so it may come back on a new address after a Wi-Fi drop.
        val sameSender = if (s.trusted) http.stream.peerFingerprint == s.sender else http.stream.remoteHost == s.host
        return if (sameSender) s to f else null
    }

    private suspend fun upload(http: HttpStream, head: HttpHead): Boolean {
        val q = head.query
        if (q["sessionId"] == null || q["fileId"] == null || q["token"] == null) return refuse(http, 400)
        val (s, f) = target(http, head) ?: return refuse(http, 403)
        if (f.finished || f.stream != null) return refuse(http, 409)
        // Bridge resume: only a paired sender's Upload-Offset counts; anyone else gets plain LocalSend.
        val resumeAt = if (s.trusted) head.headers[OFFSET_HEADER.lowercase()]?.toLongOrNull() else null
        if (resumeAt != null && resumeAt != f.received) return refuse(http, 409, mapOf(OFFSET_HEADER to "${f.received}"))
        if (resumeAt == null && f.received > 0) {
            // A plain retry starts the file over.
            f.sink?.let { sink -> withContext(io) { runCatching { sink.close(keep = false) } } }
            f.sink = null
            f.received = 0
        }
        val length = head.contentLength
        if (length != null && f.received + length != f.size) return refuse(http, 400)
        s.idle?.cancel()
        f.stream = http.stream
        setFile(s.live, f.index, TransferStatus.RUNNING, f.received)
        try {
            val sink = f.sink ?: withContext(io) { store.create(f.path) }.also { f.sink = it }
            http.readBody(head) { chunk ->
                if (s.ended) throw StreamException("cancelled")
                require(f.received + chunk.size <= f.size) { "more bytes than announced" }
                withContext(io) { sink.write(chunk) }
                f.received += chunk.size
                progress(s.live, f.index, f.received)
            }
            if (f.received != f.size) throw StreamException("upload ended at ${f.received} of ${f.size} bytes")
            withContext(io) { sink.close(keep = true) }
            f.sink = null
        } catch (e: StreamException) {
            f.stream = null
            if (s.ended) throw e
            if (s.trusted && inbound === s) {
                setFile(s.live, f.index, TransferStatus.PAUSED, f.received)
                idleTimeout(s)
            } else {
                failFile(s, f, "connection to ${s.live.peerName} lost (${e.message})")
            }
            throw e
        } catch (e: CancellationException) {
            f.stream = null
            throw e
        } catch (e: Exception) {
            // Over-long body (400) or the receive folder failed, e.g. disk full (500).
            f.stream = null
            if (!s.ended) failFile(s, f, if (e is IllegalArgumentException) "${f.path}: ${e.message}" else "couldn't save ${f.path}: ${e.message}")
            return refuse(http, if (e is IllegalArgumentException) 400 else 500)
        }
        f.stream = null
        f.finished = true
        setFile(s.live, f.index, TransferStatus.DONE, f.size)
        http.respond(200)
        settle(s)
        return true
    }

    private suspend fun uploadOffset(http: HttpStream, head: HttpHead): Boolean {
        val (s, f) = target(http, head)?.takeIf { it.first.trusted } ?: return refuse(http, 404)
        if (s.ended || f.finished) return refuse(http, 409)
        http.respond(200, headers = mapOf(OFFSET_HEADER to "${f.received}"))
        return true
    }

    private suspend fun cancelRequest(http: HttpStream, head: HttpHead): Boolean {
        val s = inbound?.takeIf { it.id == head.query["sessionId"] && (it.host == http.stream.remoteHost || it.sender == http.stream.peerFingerprint) }
        s?.let { end(it, TransferStatus.CANCELLED) }
        http.respond(200)
        return true
    }

    private fun failFile(s: Inbound, f: InFile, reason: String) {
        s.live.error = reason
        f.sink?.let { closeQuietly(it) }
        f.sink = null
        f.finished = true
        setFile(s.live, f.index, TransferStatus.FAILED)
        settle(s)
    }

    /** Ends the session once every file is done or failed; otherwise restarts the idle clock. */
    private fun settle(s: Inbound) {
        if (s.files.values.all { it.finished }) {
            end(s, if (s.live.files.all { it.status == TransferStatus.DONE }) TransferStatus.DONE else TransferStatus.FAILED)
        } else {
            idleTimeout(s)
        }
    }

    private fun idleTimeout(s: Inbound) {
        s.idle?.cancel()
        s.idle = scope.launch {
            delay(SESSION_IDLE)
            if (s.files.values.none { it.stream != null }) end(s, TransferStatus.FAILED, "${s.live.peerName} stopped sending")
        }
    }

    private fun end(s: Inbound, status: TransferStatus, error: String? = null) {
        if (s.ended) return
        s.ended = true
        if (inbound === s) inbound = null
        // May be the idle job itself; end() never suspends, so it still runs to completion.
        s.idle?.cancel()
        s.files.values.forEach { f ->
            f.stream?.close()
            f.sink?.let { closeQuietly(it) }
            f.sink = null
        }
        finish(s.live, status, error)
    }

    private fun closeQuietly(sink: FileSink) {
        scope.launch(io) { runCatching { sink.close(keep = false) } }
    }

    // ---- client ---------------------------------------------------------------------------------

    private suspend fun sendAll(l: Live, peer: LocalSendPeer, files: List<OutgoingFile>) {
        var sessionId: String? = null
        try {
            val dtos = files.map { FileDto(newId(), it.path, it.size, it.mime) }
            val body = json(PrepareUpload.serializer(), PrepareUpload(info(network = true), dtos.associateBy { it.id }))
            val (head, resp) = request(peer, "POST", "$API/prepare-upload", body, port = filesPort(peer))
            when (head.status) {
                200 -> Unit
                204 -> return finish(l, TransferStatus.DONE)
                403 -> return finish(l, TransferStatus.DECLINED)
                409 -> return finish(l, TransferStatus.FAILED, "${peer.alias} is busy with another transfer")
                else -> return finish(l, TransferStatus.FAILED, "${peer.alias} answered HTTP ${head.status}")
            }
            val r = decode(PrepareUploadResponse.serializer(), resp)
                ?: return finish(l, TransferStatus.FAILED, "${peer.alias} sent a reply Bridge can't read")
            sessionId = r.sessionId
            val resumable = head.headers[RESUME_HEADER.lowercase()] == "1"
            files.forEachIndexed { i, f ->
                val token = r.files[dtos[i].id]
                if (token == null) return@forEachIndexed setFile(l, i, TransferStatus.CANCELLED)
                try {
                    upload(l, i, peer, r.sessionId, dtos[i].id, token, f, resumable)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Like LocalSend: one failed file doesn't stop the rest.
                    l.error = "${f.path}: ${reason(e)}"
                    setFile(l, i, TransferStatus.FAILED)
                }
            }
            finish(l, if (l.files.all { it.status == TransferStatus.DONE }) TransferStatus.DONE else TransferStatus.FAILED)
        } catch (e: CancellationException) {
            finish(l, TransferStatus.CANCELLED)
            sessionId?.let { sid ->
                withContext(NonCancellable) {
                    withTimeoutOrNull(3.seconds) { runCatching { request(peer, "POST", "$API/cancel?" + encodeQuery(mapOf("sessionId" to sid)), port = filesPort(peer)) } }
                }
            }
            throw e
        } catch (e: Exception) {
            finish(l, TransferStatus.FAILED, "can't reach ${peer.alias} at ${peer.host}:${filesPort(peer)}: ${reason(e)}")
        }
    }

    private suspend fun upload(l: Live, i: Int, peer: LocalSendPeer, sessionId: String, fileId: String, token: String, f: OutgoingFile, resumable: Boolean) {
        val target = "$API/upload?" + encodeQuery(mapOf("sessionId" to sessionId, "fileId" to fileId, "token" to token))
        var offset = 0L
        var failures = 0
        setFile(l, i, TransferStatus.RUNNING, 0)
        while (true) {
            try {
                val headers = if (offset > 0) mapOf(OFFSET_HEADER to "$offset") else emptyMap()
                val status = request(peer, "POST", target, headers = headers, length = f.size - offset, port = filesPort(peer)) { write ->
                    var sent = offset
                    val src = withContext(io) { f.open(offset) }
                    try {
                        while (sent < f.size) {
                            val chunk = withContext(io) { src.read(minOf(CHUNK.toLong(), f.size - sent).toInt()) }
                            check(chunk.isNotEmpty()) { "${f.path} got shorter while sending" }
                            write(chunk)
                            sent += chunk.size
                            progress(l, i, sent)
                        }
                    } finally {
                        withContext(NonCancellable + io) { src.close() }
                    }
                }.first.status
                check(status == 200) { "upload refused ($status)" }
                return setFile(l, i, TransferStatus.DONE, f.size)
            } catch (e: StreamException) {
                if (!resumable || ++failures > MAX_RETRIES) throw e
                setFile(l, i, TransferStatus.PAUSED)
                delay(RETRY_DELAY)
                val resumed = try {
                    val (head, _) = request(peer, "HEAD", target, port = filesPort(peer))
                    check(head.status == 200) { "receiver dropped the session (${head.status})" }
                    head.headers[OFFSET_HEADER.lowercase()]?.toLongOrNull() ?: error("no $OFFSET_HEADER")
                } catch (e: StreamException) {
                    continue
                }
                if (resumed > offset) failures = 0
                offset = resumed
                setFile(l, i, TransferStatus.RUNNING, offset)
            }
        }
    }

    /**
     * One request on a fresh connection, pinned to [peer]'s fingerprint. The body is [body], or
     * [length] bytes produced by [writeBody]. Returns the response head and body.
     */
    private suspend fun request(
        peer: LocalSendPeer,
        method: String,
        target: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        length: Long = body?.size?.toLong() ?: 0,
        port: Int = peer.port,
        writeBody: (suspend (suspend (ByteArray) -> Unit) -> Unit)? = null,
    ): Pair<HttpHead, ByteArray> {
        val s = connect(peer.host, port)
        try {
            if (s.peerFingerprint != peer.fingerprint) throw StreamException("${peer.alias} presented a different certificate")
            val http = HttpStream(s)
            val all = headers + mapOf("Host" to "${peer.host}:$port", "Content-Length" to "$length", "Connection" to "close") +
                (if (body != null) mapOf("Content-Type" to "application/json") else emptyMap())
            http.writeHead("$method $target HTTP/1.1", all)
            body?.let { s.writeAll(it) }
            writeBody?.invoke { s.writeAll(it) }
            val head = http.readHead() ?: throw StreamException("no response")
            val noBody = method == "HEAD" || head.status == 204 || head.status == 304
            return head to (if (noBody) ByteArray(0) else http.readBytes(head, MAX_JSON, toEnd = true))
        } finally {
            s.close()
        }
    }

    private fun isTrusted(fingerprint: String?) = fingerprint != null && keyStore.trustedPeers().any { it.fingerprint == fingerprint }

    /** A paired peer is a Bridge device: send over its paired port, presenting our certificate. */
    private fun filesPort(peer: LocalSendPeer) = if (isTrusted(peer.fingerprint)) pairedPort else peer.port

    private suspend fun connect(host: String, port: Int): ByteStream = withTimeoutOrNull(IO_TIMEOUT) {
        suspendCancellableCoroutine<Pair<ByteStream?, String?>> { c ->
            streams.dial(host, port) { s, error -> if (c.isActive) c.resume(s to error) else s?.close() }
        }
    }.let { result -> result?.first ?: throw StreamException(result?.second ?: "connect to $host timed out") }
}

private val FINAL = setOf(TransferStatus.DONE, TransferStatus.FAILED, TransferStatus.CANCELLED, TransferStatus.DECLINED)

private fun reason(e: Exception) = e.message ?: e::class.simpleName ?: "unknown error"

private fun newId() = secureRandomBytes(16).toHexString()

private fun tokensMatch(a: String, b: String) = a.length == b.length && a.indices.fold(0) { acc, i -> acc or (a[i].code xor b[i].code) } == 0

private fun <T> json(serializer: kotlinx.serialization.KSerializer<T>, value: T) = localSendJson.encodeToString(serializer, value).encodeToByteArray()

private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, bytes: ByteArray): T? =
    runCatching { localSendJson.decodeFromString(serializer, bytes.decodeToString()) }.getOrNull()
