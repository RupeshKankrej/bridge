package bridge.files

import bridge.port.ByteStream
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

/** The connection failed, timed out, or the peer broke HTTP. */
class StreamException(message: String) : Exception(message)

/** A read or write that makes no progress this long counts as a dropped connection (Wi-Fi gone). */
internal val IO_TIMEOUT = 20.seconds
internal const val CHUNK = 256 * 1024
private const val MAX_LINE = 8 * 1024
private const val MAX_HEADERS = 100

internal suspend fun ByteStream.readSome(max: Int): ByteArray = io { c -> read(max) { data, error -> c(data, error) } }

internal suspend fun ByteStream.writeAll(data: ByteArray) {
    io<Unit> { c -> write(data) { error -> c(if (error == null) Unit else null, error) } }
}

private suspend fun <T : Any> ByteStream.io(start: ((T?, String?) -> Unit) -> Unit): T {
    val result = withTimeoutOrNull(IO_TIMEOUT) {
        suspendCancellableCoroutine<Result<T>> { c ->
            start { value, error -> if (c.isActive) c.resume(value?.let { Result.success(it) } ?: Result.failure(StreamException(error ?: "failed"))) }
        }
    }
    if (result == null) {
        close()
        throw StreamException("timed out")
    }
    return result.getOrThrow()
}

/** Start line split on spaces, and headers with lowercase names. */
internal class HttpHead(val start: List<String>, val headers: Map<String, String>) {
    val method get() = start[0]
    val path get() = start[1].substringBefore('?')
    val query: Map<String, String> get() = start[1].substringAfter('?', "").split('&').filter { it.isNotEmpty() }
        .associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=', "")) }
    val status get() = start[1].toIntOrNull() ?: 0
    val chunked get() = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
    val contentLength get() = headers["content-length"]?.toLongOrNull()
}

/**
 * Just enough HTTP/1.1 for LocalSend: request and status lines, headers, Content-Length and chunked
 * bodies, keep-alive on the server side. No pipelining, no 100-continue.
 */
internal class HttpStream(val stream: ByteStream) {
    private var buf = ByteArray(0)
    private var pos = 0

    /** False at a clean end of stream. */
    private suspend fun fill(): Boolean {
        if (pos < buf.size) return true
        buf = stream.readSome(CHUNK)
        pos = 0
        return buf.isNotEmpty()
    }

    private suspend fun readLine(): String? {
        val line = StringBuilder()
        while (true) {
            if (!fill()) return if (line.isEmpty()) null else throw StreamException("truncated line")
            val b = buf[pos++].toInt().toChar()
            if (b == '\n') return line.toString().removeSuffix("\r")
            if (line.length >= MAX_LINE) throw StreamException("line too long")
            line.append(b)
        }
    }

    /** The next request or response head; null if the peer closed between messages. */
    suspend fun readHead(): HttpHead? {
        val start = readLine() ?: return null
        val parts = start.split(' ', limit = 3)
        if (parts.size < 2) throw StreamException("bad start line")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine() ?: throw StreamException("truncated headers")
            if (line.isEmpty()) break
            if (headers.size >= MAX_HEADERS) throw StreamException("too many headers")
            val colon = line.indexOf(':').takeIf { it > 0 } ?: throw StreamException("bad header")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return HttpHead(parts, headers)
    }

    /**
     * Streams the body to [onData]. Without Content-Length or chunking, a request has no body and a
     * response runs to end of stream ([toEnd]). Throws if the body ends early.
     */
    suspend fun readBody(head: HttpHead, toEnd: Boolean = false, onData: suspend (ByteArray) -> Unit) {
        if (head.chunked) {
            while (true) {
                val size = readLine()?.substringBefore(';')?.trim()?.toLongOrNull(16) ?: throw StreamException("bad chunk")
                if (size == 0L) {
                    do {
                        val trailer = readLine() ?: throw StreamException("truncated trailer")
                    } while (trailer.isNotEmpty())
                    return
                }
                copy(size, onData)
                if (readLine() != "") throw StreamException("bad chunk end")
            }
        }
        val length = head.contentLength
        if (length != null) return copy(length, onData)
        if (toEnd) while (fill()) copy((buf.size - pos).toLong(), onData)
    }

    private suspend fun copy(length: Long, onData: suspend (ByteArray) -> Unit) {
        var left = length
        while (left > 0) {
            if (!fill()) throw StreamException("body ended early")
            val n = minOf(left, (buf.size - pos).toLong()).toInt()
            onData(if (pos == 0 && n == buf.size) buf else buf.copyOfRange(pos, pos + n))
            pos += n
            left -= n
        }
    }

    /** The whole body, refusing anything over [limit] bytes. */
    suspend fun readBytes(head: HttpHead, limit: Int, toEnd: Boolean = false): ByteArray {
        val out = mutableListOf<ByteArray>()
        var total = 0
        readBody(head, toEnd) {
            total += it.size
            if (total > limit) throw StreamException("body over $limit bytes")
            out += it
        }
        val all = ByteArray(total)
        var at = 0
        out.forEach { it.copyInto(all, at); at += it.size }
        return all
    }

    suspend fun writeHead(start: String, headers: Map<String, String>) {
        stream.writeAll(buildString {
            append(start).append("\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("\r\n")
        }.encodeToByteArray())
    }

    suspend fun respond(status: Int, body: ByteArray = ByteArray(0), headers: Map<String, String> = emptyMap(), close: Boolean = false) {
        val all = headers + ("Content-Length" to "${body.size}") +
            (if (body.isNotEmpty()) mapOf("Content-Type" to "application/json") else emptyMap()) +
            (if (close) mapOf("Connection" to "close") else emptyMap())
        writeHead("HTTP/1.1 $status ${REASONS[status] ?: "Status"}", all)
        if (body.isNotEmpty()) stream.writeAll(body)
    }
}

private val REASONS = mapOf(
    200 to "OK", 204 to "No Content", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
    409 to "Conflict", 422 to "Unprocessable Entity", 500 to "Internal Server Error",
)

private fun decode(s: String): String {
    if ('%' !in s && '+' !in s) return s
    val out = mutableListOf<Byte>()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '+' -> out += ' '.code.toByte()
            c == '%' && i + 2 < s.length && s.substring(i + 1, i + 3).toIntOrNull(16) != null -> {
                out += s.substring(i + 1, i + 3).toInt(16).toByte()
                i += 2
            }
            else -> out += c.toString().encodeToByteArray().toList()
        }
        i++
    }
    return out.toByteArray().decodeToString()
}

internal fun encodeQuery(params: Map<String, String>) = params.entries.joinToString("&") { (k, v) -> "$k=${encode(v)}" }

private fun encode(s: String) = s.encodeToByteArray().joinToString("") { b ->
    val c = b.toInt().toChar()
    if (c.isLetterOrDigit() && b >= 0 || c in "-._~") "$c" else "%" + (b.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
}
