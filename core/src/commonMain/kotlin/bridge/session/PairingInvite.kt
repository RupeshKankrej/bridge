package bridge.session

/** What the QR code carries (spec §5.1): `bridge://pair?fp=<sha256 hex>&host=<ip>&port=<n>&code=<6 digits>`. */
data class PairingInvite(val fingerprint: String, val host: String, val port: Int, val code: String) {
    fun toUri(): String = "bridge://pair?fp=$fingerprint&host=$host&port=$port&code=$code"

    companion object {
        private val FINGERPRINT = Regex("[0-9a-f]{64}")
        private val CODE = Regex("[0-9]{6}")
        private val HOST = Regex("[0-9A-Za-z.:%-]{1,253}")

        /** Null unless [uri] is a well-formed invite; it comes from a camera, so treat it as hostile. */
        fun parse(uri: String): PairingInvite? {
            val query = uri.removePrefix("bridge://pair?").takeIf { it != uri } ?: return null
            val params = query.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val fp = params["fp"]?.takeIf { FINGERPRINT.matches(it) } ?: return null
            val host = params["host"]?.takeIf { HOST.matches(it) } ?: return null
            val port = params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val code = params["code"]?.takeIf { CODE.matches(it) } ?: return null
            return PairingInvite(fp, host, port, code)
        }
    }
}
