package bridge.port

import bridge.protocol.MediaCommand
import bridge.protocol.MediaState

/**
 * Now-playing and media keys (spec §5.6). The Mac's port reads its players and runs commands; the tablet only shows
 * what core puts in [bridge.session.PeerStatus.media] and calls [bridge.session.Bridge.media], so it keeps the defaults.
 */
interface MediaPort {
    /**
     * Mac: report the now-playing state to [onState] (from the main thread) whenever it may have changed, or stop
     * when [onState] is null. Core only starts this while a peer watches, and drops repeats itself.
     */
    fun watch(onState: ((MediaState) -> Unit)?) {}

    /** Mac: run play / pause / next / prev / volume on the current player. */
    fun command(command: MediaCommand) {}
}
