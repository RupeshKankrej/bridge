import CryptoKit
import Foundation
import Network
import BridgeCore

/// TransportPort + DiscoveryPort on Network.framework. One NWListener does both jobs: it accepts
/// TLS 1.3 WebSocket connections and advertises itself over Bonjour as _bridge._tcp.
final class MacNetwork: NSObject, TransportPort, DiscoveryPort {
    private let queue = DispatchQueue(label: "dev.bridge.network")
    private let identity: () throws -> SecIdentity
    private var listener: NWListener?
    private var service: NWListener.Service?
    private var browser: NWBrowser?

    init(identity: @escaping () throws -> SecIdentity) {
        self.identity = identity
    }

    // MARK: DiscoveryPort

    func advertise(port: Int32, txt: [String: String]) {
        // Core calls advertise before listen; the listener picks the service up when it starts.
        service = NWListener.Service(name: txt["name"], type: "_bridge._tcp", domain: nil, txtRecord: NWTXTRecord(txt))
        listener?.service = service
    }

    func browse(onPeers: @escaping ([DiscoveredPeer]) -> Void) {
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: "_bridge._tcp", domain: nil), using: .tcp)
        browser.browseResultsChangedHandler = { results, _ in
            onPeers(results.compactMap { result in
                guard case .bonjour(let txt) = result.metadata, let id = txt["id"], let name = txt["name"] else { return nil }
                // No address: only the tablet dials, so the Mac just lists who is nearby.
                return DiscoveredPeer(id: id, name: name, platform: txt["platform"] ?? "", host: nil, port: 0)
            })
        }
        browser.stateUpdateHandler = { state in
            if case .failed(let error) = state { log.error("Bonjour browse failed: \(error)") }
        }
        browser.start(queue: queue)
        self.browser = browser
    }

    // MARK: TransportPort

    func listen(port: Int32, onConnection: @escaping (Connection) -> Void) {
        do {
            let listener = try NWListener(using: try parameters(), on: NWEndpoint.Port(rawValue: UInt16(port))!)
            listener.service = service
            listener.newConnectionHandler = { [queue] connection in
                MacConnection.accept(connection, queue: queue, onReady: onConnection)
            }
            listener.stateUpdateHandler = { [weak self, queue] state in
                log.info("listener on \(port, privacy: .public): \(String(describing: state), privacy: .public)")
                // A listener can fail when the network goes away (sleep, Wi-Fi switch); without it the tablet can't get back in.
                if case .failed = state {
                    listener.cancel()
                    queue.asyncAfter(deadline: .now() + 2) { self?.listen(port: port, onConnection: onConnection) }
                }
            }
            listener.start(queue: queue)
            self.listener = listener
        } catch {
            log.error("can't listen on \(port): \(error)")
        }
    }

    func connect(host: String, port: Int32, callback: @escaping (Connection?, String?) -> Void) {
        // shortcut: in M1.2 the Mac only accepts (the tablet scans the Mac's QR); add dialling when the Mac scans a tablet's QR.
        callback(nil, "the Mac doesn't dial out yet")
    }

    private func parameters() throws -> NWParameters {
        let tls = NWProtocolTLS.Options()
        let options = tls.securityProtocolOptions
        sec_protocol_options_set_min_tls_protocol_version(options, .TLSv13)
        sec_protocol_options_set_local_identity(options, sec_identity_create(try identity())!)
        // Mutual TLS: the tablet must present its certificate, so core can compare it with its fingerprint.
        sec_protocol_options_set_peer_authentication_required(options, true)
        // Self-signed certificates have no CA to chain to. Accept the handshake here; core pins the fingerprint.
        sec_protocol_options_set_verify_block(options, { _, _, complete in complete(true) }, queue)

        let parameters = NWParameters(tls: tls)
        let webSocket = NWProtocolWebSocket.Options()
        webSocket.autoReplyPing = true
        parameters.defaultProtocolStack.applicationProtocols.insert(webSocket, at: 0)
        return parameters
    }
}

/// One accepted WebSocket. Handed to core only once TLS and the WebSocket upgrade are done and the
/// peer's certificate is known. Mutable state is touched only on `queue`.
final class MacConnection: NSObject, Connection {
    let peerFingerprint: String
    private let connection: NWConnection
    private let queue: DispatchQueue
    private var listener: ConnectionListener?
    /// Frames that arrived before `start`, in order (text and binary).
    private var pending: [(ConnectionListener) -> Void] = []
    private var closedReason: String?

    static func accept(_ connection: NWConnection, queue: DispatchQueue, onReady: @escaping (Connection) -> Void) {
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                guard let fingerprint = peerFingerprint(connection) else {
                    log.error("peer presented no certificate")
                    return connection.cancel()
                }
                onReady(MacConnection(connection, fingerprint, queue))
            case .failed(let error):
                log.debug("incoming connection failed before ready: \(error)")
                connection.cancel()
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private init(_ connection: NWConnection, _ fingerprint: String, _ queue: DispatchQueue) {
        self.connection = connection
        self.peerFingerprint = fingerprint
        self.queue = queue
        super.init()
        connection.stateUpdateHandler = { [self] state in
            switch state {
            case .failed(let error): closed("\(error)")
            case .cancelled: closed("cancelled")
            default: break
            }
        }
        receive()
    }

    func start(listener: ConnectionListener) {
        queue.async { [self] in
            self.listener = listener
            pending.forEach { $0(listener) }
            pending = []
            if let closedReason { listener.onClosed(reason: closedReason) }
        }
    }

    func send(text: String) {
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        connection.send(content: Data(text.utf8), contentContext: .init(identifier: "text", metadata: [metadata]),
                        isComplete: true, completion: .idempotent)
    }

    /// Binary frames carry e.g. a notification's icon, right after the JSON message announcing it (spec §4).
    /// NWConnection sends in call order, so it follows the preceding text frame.
    func sendBinary(bytes: KotlinByteArray) {
        let metadata = NWProtocolWebSocket.Metadata(opcode: .binary)
        connection.send(content: bytes.data, contentContext: .init(identifier: "binary", metadata: [metadata]),
                        isComplete: true, completion: .idempotent)
    }

    func close(code: Int32, reason: String) {
        queue.async { [self] in
            guard closedReason == nil else { return }
            let metadata = NWProtocolWebSocket.Metadata(opcode: .close)
            metadata.closeCode = code >= 4000
                ? .privateCode(UInt16(code))
                : .protocolCode(NWProtocolWebSocket.CloseCode.Defined(rawValue: UInt16(code)) ?? .normalClosure)
            // Cancel only after the close frame is out, so the peer sees our code (e.g. 4001).
            connection.send(content: Data(reason.utf8), contentContext: .init(identifier: "close", metadata: [metadata]),
                            isComplete: true, completion: .contentProcessed { [connection] _ in connection.cancel() })
            closed(reason)
        }
    }

    private func receive() {
        connection.receiveMessage { [self] data, context, _, error in
            if let error {
                closed("\(error)")
                return connection.cancel()
            }
            let metadata = context?.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata
            switch metadata?.opcode {
            case .text:
                if let data, let text = String(data: data, encoding: .utf8) { deliver { $0.onText(text: text) } }
            case .binary:
                if let data { deliver { $0.onBinary(bytes: data.kotlin) } }
            case .close:
                closed("peer closed")
                return connection.cancel()
            default:
                break
            }
            receive()
        }
    }

    private func deliver(_ frame: @escaping (ConnectionListener) -> Void) {
        if let listener { frame(listener) } else { pending.append(frame) }
    }

    private func closed(_ reason: String) {
        guard closedReason == nil else { return }
        closedReason = reason
        listener?.onClosed(reason: reason)
    }

    static func peerFingerprint(_ connection: NWConnection) -> String? {
        guard let tls = connection.metadata(definition: NWProtocolTLS.definition) as? NWProtocolTLS.Metadata else { return nil }
        var leaf: Data?
        // The chain starts with the leaf; peers send only their self-signed certificate anyway.
        sec_protocol_metadata_access_peer_certificate_chain(tls.securityProtocolMetadata) { certificate in
            if leaf == nil { leaf = SecCertificateCopyData(sec_certificate_copy_ref(certificate).takeRetainedValue()) as Data }
        }
        return leaf.map { SHA256.hash(data: $0).map { String(format: "%02x", $0) }.joined() }
    }
}
