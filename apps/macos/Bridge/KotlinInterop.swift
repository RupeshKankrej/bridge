import Foundation
import BridgeCore
import os

let log = Logger(subsystem: "dev.bridge.mac", category: "bridge")

/// Kotlin's ByteArray arrives in Swift as KotlinByteArray (a boxed object, not Data). These copy in
/// bulk through core's BytesKt helpers; element-by-element get/set would cap file transfer far below 40 MB/s.
extension KotlinByteArray {
    var data: Data { BytesKt.nsDataOf(bytes: self) as Data }
}

extension Data {
    var kotlin: KotlinByteArray { BytesKt.byteArrayOf(data: self) }
}
