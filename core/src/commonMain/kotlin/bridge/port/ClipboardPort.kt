package bridge.port

/** The OS clipboard, text only (spec §5.2). Implementations must never log clipboard contents. */
interface ClipboardPort {
    /**
     * Calls [onLocalChange] when the user copies text on this device. Skips items marked by [write]
     * (the Bridge origin tag) and items flagged sensitive (password managers).
     */
    fun watch(onLocalChange: (String) -> Unit)

    /** Replaces the OS clipboard with [text] received from a peer, marked with the Bridge origin tag. */
    fun write(text: String)
}
