package bridge.port

/** mDNS `_bridge._tcp` (spec §5.1). TXT keys: id, name, platform, v, caps. */
interface DiscoveryPort {
    fun advertise(port: Int, txt: Map<String, String>)

    /** Calls [onPeers] with the full current list on every change. Default no-op for platforms that only advertise. */
    fun browse(onPeers: (List<DiscoveredPeer>) -> Unit) {}
}

data class DiscoveredPeer(val id: String, val name: String, val platform: String)
