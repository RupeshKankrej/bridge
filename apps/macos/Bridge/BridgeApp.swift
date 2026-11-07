import AppKit
import CoreImage.CIFilterBuiltins
import SwiftUI
import BridgeCore

@main
struct BridgeApp: App {
    @StateObject private var model = BridgeModel()

    var body: some Scene {
        // LSUIElement (Info.plist) hides the Dock icon; this scene is the whole UI.
        MenuBarExtra("Bridge", systemImage: model.isConnected ? "link.circle.fill" : "link.circle") {
            MenuView(model: model)
        }
        .menuBarExtraStyle(.window)
    }
}

@MainActor
final class BridgeModel: ObservableObject {
    @Published private(set) var state: BridgeState?
    @Published private(set) var problem: String?
    private let bridge: Bridge
    private let activity: NSObjectProtocol

    var isConnected: Bool { state?.paired.contains { $0.online } ?? false }

    init() {
        let keyStore = MacKeyStore()
        let network = MacNetwork(identity: keyStore.tlsIdentity)
        bridge = Bridge(
            name: Host.current().localizedName ?? "Mac",
            platform: .macos,
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0",
            keyStore: keyStore,
            transport: network,
            discovery: network
        )
        // App Nap would stretch the 15 s heartbeat timers of a windowless app until the tablet drops us.
        activity = ProcessInfo.processInfo.beginActivity(options: .userInitiatedAllowingIdleSystemSleep, reason: "Keep the tablet session alive")
        bridge.observe { [weak self] state in
            // Core delivers on Dispatchers.Main, which is the main queue.
            MainActor.assumeIsolated { self?.state = state }
        }
        bridge.start()
    }

    func openPairing() {
        guard let ip = lanIPv4Address() else {
            problem = "No Wi-Fi or Ethernet address; connect to the same network as the tablet"
            return
        }
        problem = nil
        bridge.openPairing(host: ip)
    }

    func closePairing() { bridge.closePairing() }
    func cancelPairing() { bridge.rejectPairing() }
    func forget(_ fingerprint: String) { bridge.unpair(fingerprint: fingerprint) }
}

struct MenuView: View {
    @ObservedObject var model: BridgeModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let state = model.state {
                ForEach(state.paired, id: \.fingerprint) { peer in
                    HStack {
                        Circle().fill(peer.online ? Color.green : Color.secondary).frame(width: 8, height: 8)
                        Text("Paired with \(peer.name)")
                        Spacer()
                        Button("Forget") { model.forget(peer.fingerprint) }
                    }
                }

                if let pairing = state.pairing {
                    Text("Confirm on \(pairing.peerName)").font(.headline)
                    code(pairing.code)
                    Button("Cancel") { model.cancelPairing() }
                } else if let invite = state.invite {
                    Text("Scan with Bridge on your tablet").font(.headline)
                    if let image = qrImage(invite.toUri()) {
                        Image(nsImage: image).interpolation(.none).resizable().frame(width: 220, height: 220)
                    }
                    code(invite.code)
                    Button("Cancel") { model.closePairing() }
                } else {
                    Button("Pair a device…") { model.openPairing() }
                }

                if let message = model.problem ?? state.message {
                    Text(message).font(.caption).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true)
                }

                Divider()
                Text("Nearby").font(.caption).foregroundStyle(.secondary)
                if state.discovered.isEmpty {
                    Text("No devices found").foregroundStyle(.secondary)
                }
                ForEach(state.discovered, id: \.id) { peer in
                    Label(peer.name, systemImage: peer.platform == "android" ? "ipad.landscape" : "laptopcomputer")
                }
            }
            Divider()
            Button("Quit Bridge") { NSApp.terminate(nil) }
        }
        .padding()
        .frame(width: 280)
    }

    private func code(_ code: String) -> some View {
        Text(code).font(.system(size: 32, weight: .semibold, design: .monospaced))
    }
}

private func qrImage(_ text: String) -> NSImage? {
    let filter = CIFilter.qrCodeGenerator()
    filter.message = Data(text.utf8)
    filter.correctionLevel = "M"
    guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else { return nil }
    let rep = NSCIImageRep(ciImage: output)
    let image = NSImage(size: rep.size)
    image.addRepresentation(rep)
    return image
}

/// First IPv4 address on a Wi-Fi/Ethernet interface (en*), skipping VPN tunnels and loopback.
private func lanIPv4Address() -> String? {
    var list: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&list) == 0, let first = list else { return nil }
    defer { freeifaddrs(list) }
    for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
        let ifa = pointer.pointee
        guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
              ifa.ifa_flags & UInt32(IFF_UP) != 0, ifa.ifa_flags & UInt32(IFF_LOOPBACK) == 0,
              String(cString: ifa.ifa_name).hasPrefix("en")
        else { continue }
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
            return String(cString: host)
        }
    }
    return nil
}
