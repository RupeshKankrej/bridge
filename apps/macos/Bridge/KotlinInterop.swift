import Foundation
import BridgeCore
import os

let log = Logger(subsystem: "dev.bridge.mac", category: "bridge")

/// Kotlin's ByteArray arrives in Swift as KotlinByteArray (a boxed object, not Data). Our payloads
/// are certificates and signatures (< 1 KB), so a byte-by-byte copy is fine.
extension KotlinByteArray {
    convenience init(data: Data) {
        self.init(size: Int32(data.count))
        for (i, byte) in data.enumerated() { set(index: Int32(i), value: Int8(bitPattern: byte)) }
    }

    var data: Data { Data((0..<size).map { UInt8(bitPattern: get(index: $0)) }) }
}
