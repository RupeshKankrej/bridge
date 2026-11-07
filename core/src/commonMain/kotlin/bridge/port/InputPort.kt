package bridge.port

import bridge.protocol.InputKey
import bridge.protocol.InputPointer

/**
 * Remote input (spec §5.5): the Mac injects what the tablet sends. The tablet's UI calls [bridge.session.Bridge.pointer]
 * and friends directly; platforms that don't take input keep these defaults.
 */
interface InputPort {
    /** Returns false if the OS refuses to inject events (macOS: Accessibility permission missing). */
    fun pointer(event: InputPointer): Boolean = false

    /** Same contract as [pointer]. Never log [InputKey.text]. */
    fun key(event: InputKey): Boolean = false
}
