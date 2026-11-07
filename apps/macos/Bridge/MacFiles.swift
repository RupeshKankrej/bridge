import Foundation
import Network
import UniformTypeIdentifiers
import BridgeCore

/// StreamPort on Network.framework: plain TLS 1.3 byte streams (no WebSocket) for the LocalSend file API.
/// Network.framework can't ask for an *optional* client certificate on macOS, which is why paired peers
/// use their own port that requires one (ADR 0007).
final class MacStreams: NSObject, StreamPort {
    private let queue = DispatchQueue(label: "dev.bridge.files")
    private let identity: () throws -> SecIdentity
    private let onProblem: (String) -> Void
    private var listeners: [NWListener] = []

    init(identity: @escaping () throws -> SecIdentity, onProblem: @escaping (String) -> Void) {
        self.identity = identity
        self.onProblem = onProblem
    }

    func listen(port: Int32, requireClientCertificate: Bool, onStream: @escaping (ByteStream) -> Void) {
        do {
            let listener = try NWListener(using: try parameters(server: true, requireClientCertificate: requireClientCertificate),
                                          on: NWEndpoint.Port(rawValue: UInt16(port))!)
            listener.newConnectionHandler = { [queue] connection in
                connection.stateUpdateHandler = { state in
                    switch state {
                    case .ready:
                        connection.stateUpdateHandler = nil
                        onStream(MacByteStream(connection))
                    case .failed(let error):
                        // Includes a stranger without a certificate on the paired port.
                        log.debug("file connection failed before ready: \(error)")
                        connection.cancel()
                    default:
                        break
                    }
                }
                connection.start(queue: queue)
            }
            listener.stateUpdateHandler = { [onProblem] state in
                if case .failed(let error) = state {
                    log.error("file listener on \(port) failed: \(error)")
                    onProblem("Can't receive files: port \(port) is in use (is LocalSend running on this Mac?)")
                }
            }
            listener.start(queue: queue)
            listeners.append(listener)
        } catch {
            log.error("can't listen on \(port): \(error)")
            onProblem("Can't receive files on port \(port)")
        }
    }

    func dial(host: String, port: Int32, callback: @escaping (ByteStream?, String?) -> Void) {
        let parameters: NWParameters
        do {
            parameters = try self.parameters(server: false, requireClientCertificate: false)
        } catch {
            return callback(nil, "\(error)")
        }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: UInt16(port))!, using: parameters)
        var answered = false // touched only on queue
        connection.stateUpdateHandler = { state in
            guard !answered else { return }
            switch state {
            case .ready:
                answered = true
                callback(MacByteStream(connection), nil)
            case .waiting(let error), .failed(let error):
                // .waiting means unreachable for now; core retries on its own schedule, so give up here.
                answered = true
                connection.cancel()
                callback(nil, "\(error)")
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func parameters(server: Bool, requireClientCertificate: Bool) throws -> NWParameters {
        let tls = NWProtocolTLS.Options()
        let options = tls.securityProtocolOptions
        sec_protocol_options_set_min_tls_protocol_version(options, .TLSv13)
        // Clients present it too, so a paired receiver's port 53319 can tell who we are.
        sec_protocol_options_set_local_identity(options, sec_identity_create(try identity())!)
        if server { sec_protocol_options_set_peer_authentication_required(options, requireClientCertificate) }
        // Self-signed certificates chain to no CA. Accept any; core pins fingerprints.
        sec_protocol_options_set_verify_block(options, { _, _, complete in complete(true) }, queue)
        return NWParameters(tls: tls)
    }
}

/// One TLS connection. Each read/write gets exactly one completion, which is what core expects.
final class MacByteStream: NSObject, ByteStream {
    private let connection: NWConnection
    let peerFingerprint: String?
    let remoteHost: String

    init(_ connection: NWConnection) {
        self.connection = connection
        peerFingerprint = MacConnection.peerFingerprint(connection)
        if case .hostPort(let host, _) = connection.endpoint { remoteHost = ipString(host) } else { remoteHost = "" }
    }

    func read(max: Int32, callback: @escaping (KotlinByteArray?, String?) -> Void) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: Int(max)) { data, _, isComplete, error in
            if let data, !data.isEmpty {
                callback(data.kotlin, nil)
            } else if let error {
                callback(nil, "\(error)")
            } else if isComplete {
                callback(KotlinByteArray(size: 0), nil)
            } else {
                callback(nil, "no data")
            }
        }
    }

    func write(data: KotlinByteArray, callback: @escaping (String?) -> Void) {
        connection.send(content: data.data, completion: .contentProcessed { error in callback(error.map { "\($0)" }) })
    }

    func close() { connection.cancel() }
}

/// MulticastPort: LocalSend's discovery group. Reuses the local port so it can coexist with LocalSend.app.
final class MacMulticast: NSObject, MulticastPort {
    private let queue = DispatchQueue(label: "dev.bridge.multicast")
    private var group: NWConnectionGroup?
    private var ready = false
    private var pending: [Data] = []

    func join(onDatagram: @escaping (KotlinByteArray, String) -> Void) {
        do {
            let description = try NWMulticastGroup(for: [.hostPort(host: "224.0.0.167", port: 53317)])
            let parameters = NWParameters.udp
            parameters.allowLocalEndpointReuse = true
            let group = NWConnectionGroup(with: description, using: parameters)
            group.setReceiveHandler(maximumMessageSize: 65_535, rejectOversizedMessages: true) { message, content, _ in
                guard let content, case .hostPort(let host, _)? = message.remoteEndpoint else { return }
                onDatagram(content.kotlin, ipString(host))
            }
            group.stateUpdateHandler = { [self] state in
                switch state {
                case .ready:
                    ready = true
                    pending.forEach(send)
                    pending = []
                case .failed(let error):
                    log.error("multicast failed: \(error)")
                default:
                    break
                }
            }
            group.start(queue: queue)
            self.group = group
        } catch {
            log.error("can't join LocalSend multicast: \(error)")
        }
    }

    func send(data: KotlinByteArray) {
        let bytes = data.data
        queue.async { [self] in ready ? send(bytes) : pending.append(bytes) }
    }

    private func send(_ bytes: Data) {
        group?.send(content: bytes) { error in
            if let error { log.debug("multicast send failed: \(error)") }
        }
    }
}

private func ipString(_ host: NWEndpoint.Host) -> String {
    switch host {
    case .ipv4(let address): return "\(address)"
    // LocalSend compares sender addresses; report v4-mapped IPv6 as plain IPv4.
    case .ipv6(let address): return address.asIPv4.map { "\($0)" } ?? "\(address)"
    case .name(let name, _): return name
    @unknown default: return "\(host)"
    }
}

/// FileStorePort: `~/Downloads/Bridge`. Writes to a hidden temporary file next to the target and
/// moves it into place, under a free name, only when the transfer completes.
final class MacFileStore: NSObject, FileStorePort {
    static let defaultFolder = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0].appendingPathComponent("Bridge")
    private static let key = "receiveFolder"

    /// Settings → Receive folder (spec §7); read per file, so a change applies to the next transfer. Not sandboxed, so a path is enough.
    static var folder: URL {
        get { UserDefaults.standard.string(forKey: key).map { URL(fileURLWithPath: $0, isDirectory: true) } ?? defaultFolder }
        set { UserDefaults.standard.set(newValue == defaultFolder ? nil : newValue.path, forKey: key) }
    }

    func create(path: String) throws -> FileSink {
        let root = Self.folder.standardizedFileURL
        let target = root.appendingPathComponent(path).standardizedFileURL
        // core already strips "..", but this is the trust boundary on disk.
        guard target.path.hasPrefix(root.path + "/") else { throw CocoaError(.fileWriteInvalidFileName) }
        let dir = target.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let part = dir.appendingPathComponent(".\(target.lastPathComponent).\(UUID().uuidString).bridgepart")
        guard FileManager.default.createFile(atPath: part.path, contents: nil) else { throw CocoaError(.fileWriteUnknown) }
        return MacFileSink(handle: try FileHandle(forWritingTo: part), part: part, target: target)
    }
}

final class MacFileSink: NSObject, FileSink {
    private let handle: FileHandle
    private let part: URL
    private let target: URL

    init(handle: FileHandle, part: URL, target: URL) {
        self.handle = handle
        self.part = part
        self.target = target
    }

    func write(data: KotlinByteArray) throws { try handle.write(contentsOf: data.data) }

    func close(keep: Bool) throws {
        try? handle.close()
        guard keep else {
            try? FileManager.default.removeItem(at: part)
            return
        }
        try FileManager.default.moveItem(at: part, to: freeName(target))
    }

    /// "a.txt", then "a (1).txt", "a (2).txt"…
    private func freeName(_ url: URL) -> URL {
        let stem = url.deletingPathExtension().lastPathComponent
        let ext = url.pathExtension
        var candidate = url
        var n = 1
        while FileManager.default.fileExists(atPath: candidate.path) {
            let name = ext.isEmpty ? "\(stem) (\(n))" : "\(stem) (\(n)).\(ext)"
            candidate = url.deletingLastPathComponent().appendingPathComponent(name)
            n += 1
        }
        return candidate
    }
}

/// A file to send. Folders are expanded by `expand`, keeping their structure in `path`.
final class MacOutgoingFile: NSObject, OutgoingFile {
    let url: URL
    let path: String
    let size: Int64
    let mime: String
    let preview: String? = nil // files only; text messages are a tablet feature

    private init(url: URL, path: String, size: Int64) {
        self.url = url
        self.path = path
        self.size = size
        mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
    }

    func open(offset: Int64) throws -> FileSource {
        let handle = try FileHandle(forReadingFrom: url)
        try handle.seek(toOffset: UInt64(offset))
        return MacFileSource(handle)
    }

    /// Regular files as-is; folders become their regular files with "Folder/sub/name" paths. Hidden files
    /// and symlinks are skipped.
    static func expand(_ urls: [URL]) -> [MacOutgoingFile] {
        let keys: [URLResourceKey] = [.isRegularFileKey, .isDirectoryKey, .fileSizeKey]
        return urls.flatMap { url -> [MacOutgoingFile] in
            guard let values = try? url.resourceValues(forKeys: Set(keys)) else { return [] }
            if values.isRegularFile == true {
                return [MacOutgoingFile(url: url, path: url.lastPathComponent, size: Int64(values.fileSize ?? 0))]
            }
            guard values.isDirectory == true,
                  let walker = FileManager.default.enumerator(at: url, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles])
            else { return [] }
            let base = url.deletingLastPathComponent().standardizedFileURL.pathComponents.count
            return walker.compactMap { item -> MacOutgoingFile? in
                guard let file = item as? URL, let v = try? file.resourceValues(forKeys: Set(keys)), v.isRegularFile == true else { return nil }
                let relative = file.standardizedFileURL.pathComponents.dropFirst(base).joined(separator: "/")
                return MacOutgoingFile(url: file, path: relative, size: Int64(v.fileSize ?? 0))
            }
        }
    }
}

final class MacFileSource: NSObject, FileSource {
    private let handle: FileHandle

    init(_ handle: FileHandle) { self.handle = handle }

    func read(max: Int32) throws -> KotlinByteArray { (try handle.read(upToCount: Int(max)) ?? Data()).kotlin }

    func close() { try? handle.close() }
}
