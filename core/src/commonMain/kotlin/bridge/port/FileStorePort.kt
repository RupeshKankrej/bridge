package bridge.port

/**
 * The receive folder (`~/Downloads/Bridge`, `Download/Bridge`; spec §5.3). core calls it from a
 * background thread, so blocking I/O is fine.
 */
interface FileStorePort {
    /**
     * Starts a file at [path]: relative, '/'-separated, already stripped of `..` and empty segments.
     * Write to a temporary name; the file appears under [path] (or a free variant like "a (1).txt") only on
     * [FileSink.close] with `keep = true`.
     */
    @Throws(Exception::class)
    fun create(path: String): FileSink
}

interface FileSink {
    @Throws(Exception::class)
    fun write(data: ByteArray)

    /** [keep]: move into place; otherwise delete what was written. */
    @Throws(Exception::class)
    fun close(keep: Boolean)
}

/** A file the user picked to send. Called from a background thread. */
interface OutgoingFile {
    /** Relative path; '/' keeps folder structure (e.g. "Photos/a.jpg"). */
    val path: String
    val size: Long
    val mime: String

    /** Opens the file positioned at [offset] (non-zero only when resuming). */
    @Throws(Exception::class)
    fun open(offset: Long): FileSource
}

interface FileSource {
    /** Up to [max] bytes; empty at end of file. */
    @Throws(Exception::class)
    fun read(max: Int): ByteArray

    fun close()
}
