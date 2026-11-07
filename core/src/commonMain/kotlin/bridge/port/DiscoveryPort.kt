package bridge.port

/** mDNS `_bridge._tcp` (spec §5.1). TXT keys: id, name, platform, v, caps. */
interface DiscoveryPort {
    fun advertise(port: Int, txt: Map<String, String>)

    /** Calls [onPeers] with the full current list on every change. Default no-op for platforms that only advertise. */
    fun browse(onPeers: (List<DiscoveredPeer>) -> Unit) {}
}

/** [host]/[port] are the resolved address, or null/0 where the platform doesn't resolve (the Mac only lists nearby devices). */
data class DiscoveredPeer(val id: String, val name: String, val platform: String, val host: String? = null, val port: Int = 0)
