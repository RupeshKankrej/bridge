import AppKit
import CoreImage.CIFilterBuiltins
import ServiceManagement
import SwiftUI
import BridgeCore

/// The menu-bar popover: device card, pairing, files, nearby. Settings and diagnostics open in their own window.
struct MenuView: View {
    @ObservedObject var model: BridgeModel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Image(systemName: model.isConnected ? "link.circle.fill" : "link.circle")
                    .font(.title).foregroundStyle(model.isConnected ? Color.accentColor : .secondary)
                Text("Bridge").font(.title2.weight(.semibold))
                Spacer()
                Button { model.openSettings() } label: { Image(systemName: "gearshape") }
                    .buttonStyle(.borderless).help("Settings")
            }

            if let state = model.state {
                ForEach(state.paired, id: \.fingerprint) { DeviceCard(peer: $0) }
                pairing(state)
                if let message = model.problem ?? state.message { Banner(text: message) }
                FilesSection(model: model)
                nearby(state)
            }

            Divider()
            HStack {
                Button("Diagnostics…") { model.openSettings(.diagnostics) }
                Spacer()
                Button("Quit Bridge") { NSApp.terminate(nil) }
            }
            .buttonStyle(.borderless)
        }
        .padding(16)
        .frame(width: 360)
    }

    @ViewBuilder
    private func pairing(_ state: BridgeState) -> some View {
        if let pairing = state.pairing {
            Card {
                Text("Confirm on \(pairing.peerName)").font(.headline)
                code(pairing.code)
                Button("Cancel") { model.cancelPairing() }
            }
        } else if let invite = state.invite {
            Card {
                Text("Scan with Bridge on your tablet").font(.headline)
                if let image = qrImage(invite.toUri()) {
                    Image(nsImage: image).interpolation(.none).resizable().frame(width: 220, height: 220)
                        .frame(maxWidth: .infinity)
                }
                code(invite.code)
                Button("Cancel") { model.closePairing() }
            }
        } else if state.paired.isEmpty {
            Button { model.openPairing() } label: { Label("Pair a device…", systemImage: "qrcode").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).controlSize(.large)
        } else {
            Button("Pair another device…") { model.openPairing() }.buttonStyle(.borderless)
        }
    }

    @ViewBuilder
    private func nearby(_ state: BridgeState) -> some View {
        let others = state.discovered.filter { d in !state.paired.contains { $0.fingerprint == d.id } }
        if !others.isEmpty {
            Text("Nearby").font(.caption).foregroundStyle(.secondary)
            ForEach(others, id: \.id) { peer in
                Label(peer.name, systemImage: peer.platform == "android" ? "ipad.landscape" : "laptopcomputer")
            }
        }
    }

    private func code(_ code: String) -> some View {
        Text(code).font(.system(size: 32, weight: .semibold, design: .monospaced)).frame(maxWidth: .infinity)
    }
}

/// Paired tablet: status, link quality, battery.
private struct DeviceCard: View {
    let peer: PeerStatus

    var body: some View {
        Card {
            HStack(spacing: 12) {
                Image(systemName: "ipad.landscape").font(.largeTitle).foregroundStyle(peer.online ? Color.accentColor : .secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(peer.name).font(.headline)
                    HStack(spacing: 6) {
                        Circle().fill(peer.online ? Color.green : Color.secondary).frame(width: 7, height: 7)
                        Text(peer.online ? "Connected" : "Offline, waiting for the tablet").foregroundStyle(.secondary)
                    }.font(.caption)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    if let battery = peer.battery {
                        Label(batteryText(battery), systemImage: battery.charging ? "battery.100.bolt" : "battery.75").font(.caption)
                    }
                    if peer.online, let rtt = peer.rttMs { Text(qualityText(rtt.int64Value)).font(.caption).foregroundStyle(.secondary) }
                }
            }
        }
    }
}

/// A rounded, tinted group, like the cards on the tablet.
struct Card<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) { content }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12).fill(.quaternary.opacity(0.6)))
    }
}

struct Banner: View {
    let text: String

    var body: some View {
        Label(text, systemImage: "exclamationmark.triangle.fill")
            .font(.caption).foregroundStyle(.red)
            .fixedSize(horizontal: false, vertical: true)
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 10).fill(Color.red.opacity(0.1)))
    }
}

/// e.g. "Good · 12 ms"; thresholds shared with the tablet via core.
func qualityText(_ rttMs: Int64) -> String {
    let quality: String
    switch BridgeKt.linkQuality(rttMs: rttMs) {
    case .excellent: quality = "Excellent"
    case .good: quality = "Good"
    case .fair: quality = "Fair"
    default: quality = "Poor"
    }
    return "\(quality) · \(rttMs) ms"
}

// MARK: Settings window

enum SettingsTab: Hashable { case general, diagnostics }

struct SettingsView: View {
    @ObservedObject var model: BridgeModel

    var body: some View {
        TabView(selection: $model.settingsTab) {
            GeneralSettings(model: model).tabItem { Label("General", systemImage: "gearshape") }.tag(SettingsTab.general)
            DiagnosticsView(model: model).tabItem { Label("Diagnostics", systemImage: "stethoscope") }.tag(SettingsTab.diagnostics)
        }
        .frame(minWidth: 520, minHeight: 480)
    }
}

private struct GeneralSettings: View {
    @ObservedObject var model: BridgeModel

    var body: some View {
        Form {
            Section("Paired devices") {
                if model.state?.paired.isEmpty ?? true { Text("None. Use Pair a device… in the menu bar.").foregroundStyle(.secondary) }
                ForEach(model.state?.paired ?? [], id: \.fingerprint) { peer in
                    LabeledContent(peer.name) {
                        Button("Forget") { model.forget(peer.fingerprint) }
                    }
                }
            }
            Section("Files") {
                LabeledContent("Receive folder") {
                    HStack {
                        Text(model.receiveFolder.path).lineLimit(1).truncationMode(.middle).foregroundStyle(.secondary)
                        Button("Change…") { chooseFolder() }
                        if model.receiveFolder != MacFileStore.defaultFolder {
                            Button("Reset") { model.setReceiveFolder(nil) }
                        }
                    }
                }
                Text("Paired devices send without asking; others (LocalSend) get an Accept / Decline card.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("General") {
                Toggle("Open at Login", isOn: Binding(get: { model.launchAtLogin == .enabled }, set: { model.setLaunchAtLogin($0) }))
                if model.launchAtLogin == .requiresApproval {
                    // macOS blocked it (e.g. turned off under Login Items once); only the user can allow it there.
                    Button("Allow in Login Items…") { SMAppService.openSystemSettingsLoginItems() }
                }
            }
        }
        .formStyle(.grouped)
    }

    private func chooseFolder() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.canCreateDirectories = true
        panel.directoryURL = model.receiveFolder
        panel.prompt = "Use Folder"
        if panel.runModal() == .OK, let url = panel.url { model.setReceiveFolder(url) }
    }
}

/// M2.5 diagnostics: connection state, last error, peer address, link quality, crash log. Nothing leaves the Mac.
private struct DiagnosticsView: View {
    @ObservedObject var model: BridgeModel
    @State private var crashLog = CrashLog.read()

    var body: some View {
        // Re-reads "x min ago" every few seconds.
        TimelineView(.periodic(from: .now, by: 5)) { context in
            Form {
                ForEach(model.state?.paired ?? [], id: \.fingerprint) { peer in
                    Section(peer.name) {
                        LabeledContent("Connection", value: peer.online ? "Connected \(ago(peer.connectedAt, context.date))" : "Offline, waiting for the tablet to dial")
                        LabeledContent("Peer address", value: peer.address ?? "—")
                        LabeledContent("Link quality", value: peer.online ? peer.rttMs.map { qualityText($0.int64Value) + " round trip" } ?? "measuring…" : "—")
                        LabeledContent("Battery", value: peer.battery.map(batteryText) ?? "not reported")
                    }
                }
                Section("Last error") {
                    if let error = model.state?.lastError {
                        LabeledContent(error, value: ago(model.state?.lastErrorAt ?? 0, context.date))
                    } else {
                        Text("None since Bridge started").foregroundStyle(.secondary)
                    }
                    if let problem = model.problem { Text(problem).foregroundStyle(.red) }
                }
                Section("This Mac") {
                    LabeledContent("Device ID", value: shortId(model.state?.deviceId ?? ""))
                    LabeledContent("Address", value: lanIPv4Address() ?? "no Wi-Fi or Ethernet")
                    LabeledContent("Listening", value: "53318 control · 53317 LocalSend · 53319 paired files")
                    LabeledContent("Accessibility (trackpad, media keys)", value: AXIsProcessTrusted() ? "Allowed" : "Not allowed")
                    LabeledContent("Nearby (mDNS)", value: (model.state?.discovered ?? []).map(\.name).joined(separator: ", ").ifEmpty("none"))
                    LabeledContent("Nearby (LocalSend)", value: (model.files?.nearby ?? []).map { "\($0.alias) \($0.host)" }.joined(separator: ", ").ifEmpty("none"))
                    LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?")
                }
                Section("Crash log") {
                    if crashLog.isEmpty {
                        Text("No crashes recorded (\(CrashLog.url.path))").foregroundStyle(.secondary)
                    } else {
                        ScrollView {
                            Text(crashLog.suffix(4000)).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }.frame(height: 160)
                    }
                    HStack {
                        if !crashLog.isEmpty {
                            Button("Copy") {
                                NSPasteboard.general.clearContents()
                                NSPasteboard.general.setString(crashLog, forType: .string)
                            }
                            Button("Clear") { CrashLog.clear(); crashLog = "" }
                        }
                        let reports = CrashLog.systemCrashes()
                        Button("macOS crash reports (\(reports.count))…") {
                            if let newest = reports.first { NSWorkspace.shared.activateFileViewerSelecting([newest]) }
                            else { NSWorkspace.shared.open(CrashLog.systemReports) }
                        }
                    }
                }
            }
            .formStyle(.grouped)
        }
        .safeAreaInset(edge: .bottom) {
            HStack { Spacer(); Button("Reconnect now") { model.reconnect() } }.padding()
        }
        .onAppear { crashLog = CrashLog.read() }
    }

    private func ago(_ epochMs: Int64, _ now: Date) -> String {
        guard epochMs > 0 else { return "" }
        return RelativeDateTimeFormatter().localizedString(for: Date(timeIntervalSince1970: Double(epochMs) / 1000), relativeTo: now)
    }

    private func shortId(_ id: String) -> String {
        stride(from: 0, to: min(id.count, 32), by: 4).map { i in
            let start = id.index(id.startIndex, offsetBy: i)
            return String(id[start..<id.index(start, offsetBy: 4)])
        }.joined(separator: " ") + " …"
    }
}

private extension String {
    func ifEmpty(_ other: String) -> String { isEmpty ? other : self }
}

func qrImage(_ text: String) -> NSImage? {
    let filter = CIFilter.qrCodeGenerator()
    filter.message = Data(text.utf8)
    filter.correctionLevel = "M"
    guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else { return nil }
    let rep = NSCIImageRep(ciImage: output)
    let image = NSImage(size: rep.size)
    image.addRepresentation(rep)
    return image
}
