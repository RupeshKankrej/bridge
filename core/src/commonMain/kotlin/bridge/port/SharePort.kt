package bridge.port

import bridge.protocol.ShareText

/** Opens a link or text the peer shared (F12). Never log either. */
interface SharePort {
    /** [share] has exactly one of url (http/https, checked by core) or text. Returns false if the user turned receiving off. */
    fun open(share: ShareText): Boolean
}
