package bridge.files

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** LocalSend v2.1 JSON bodies (https://github.com/localsend/protocol). Field names are the wire names. */
internal const val LOCALSEND_VERSION = "2.1"
internal const val LOCALSEND_PORT = 53317
internal const val MULTICAST_GROUP = "224.0.0.167"
internal const val API = "/api/localsend/v2"

/** Bridge-only (ADR 0007): sent on prepare-upload responses to trusted Bridge clients only. */
internal const val RESUME_HEADER = "X-Bridge-Resume"

/** Bridge-only (ADR 0007): HEAD /upload reports it; POST /upload with it appends from there. */
internal const val OFFSET_HEADER = "Upload-Offset"

/** Announce, /register and /info bodies. */
@Serializable
internal data class DeviceInfo(
    val alias: String,
    val version: String = LOCALSEND_VERSION,
    val deviceModel: String? = null,
    val deviceType: String? = null,
    val fingerprint: String = "",
    val port: Int? = null,
    val protocol: String? = null,
    val download: Boolean? = null,
    /** v2 name; LocalSend still sends the v1 name [announcement] alongside it, and so do we. */
    val announce: Boolean? = null,
    val announcement: Boolean? = null,
)

@Serializable
internal data class FileDto(
    val id: String,
    val fileName: String,
    val size: Long,
    val fileType: String,
    val sha256: String? = null,
    val preview: String? = null,
)

@Serializable
internal data class PrepareUpload(val info: DeviceInfo, val files: Map<String, FileDto>)

/** `files`: file ID → upload token. */
@Serializable
internal data class PrepareUploadResponse(val sessionId: String, val files: Map<String, String>)

internal val localSendJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * [fileName] from a remote sender as a safe relative path: '/' or '\' separated, without empty,
 * `.` or `..` segments or control characters. Null if nothing is left.
 */
internal fun safePath(fileName: String): String? = fileName.split('/', '\\')
    .map { seg -> seg.filter { it >= ' ' && it != '\u007f' }.trim() }
    .filter { it.isNotEmpty() && it != "." && it != ".." }
    .joinToString("/")
    .takeIf { it.isNotEmpty() }
