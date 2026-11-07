@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package bridge.interop

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * Bulk copies between Swift's Data and Kotlin's ByteArray. Swift sees ByteArray as KotlinByteArray,
 * whose element-by-element get/set is far too slow for 40 MB/s of file data.
 */
fun byteArrayOf(data: NSData): ByteArray {
    val bytes = ByteArray(data.length.toInt())
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return bytes
}

fun nsDataOf(bytes: ByteArray): NSData =
    if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
