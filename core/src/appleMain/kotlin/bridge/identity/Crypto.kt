@file:OptIn(ExperimentalForeignApi::class)

package bridge.identity

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

internal actual fun sha256(data: ByteArray): ByteArray {
    val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
    data.usePinned { d -> out.usePinned { o -> CC_SHA256(if (data.isEmpty()) null else d.addressOf(0), data.size.convert(), o.addressOf(0).reinterpret()) } }
    return out
}

internal actual fun secureRandomBytes(size: Int): ByteArray {
    val out = ByteArray(size)
    val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) }
    check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
    return out
}
