package bridge.identity

internal expect fun sha256(data: ByteArray): ByteArray

internal expect fun secureRandomBytes(size: Int): ByteArray
