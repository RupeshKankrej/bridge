import AppKit
import SwiftUI
import BridgeCore

/// Files part of the popover: an incoming offer, a device picker for pending sends, and the transfer list.
struct FilesSection: View {
    @ObservedObject var model: BridgeModel

    var body: some View {
        if let files = model.files {
            if let offer = files.offer { OfferView(offer: offer, model: model) }
            if !model.pendingSend.isEmpty { pickDevice(files) }

            Divider()
            HStack {
                Text("Files").font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button("Send files…") { chooseFiles() }
                Button { NSWorkspace.shared.open(receiveFolder()) } label: { Image(systemName: "folder") }
                    .help("Open \(MacFileStore.folder.path)")
            }
            // Newest first; the list only grows within a run.
            ForEach(files.transfers.suffix(5).reversed(), id: \.id) { TransferRow(transfer: $0, model: model) }
        }
    }

    private func pickDevice(_ files: FilesState) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Send \(model.pendingSend.count) file\(model.pendingSend.count == 1 ? "" : "s") to…").font(.headline)
            if files.nearby.isEmpty { Text("No LocalSend or Bridge devices found").foregroundStyle(.secondary) }
            ForEach(files.nearby, id: \.fingerprint) { peer in
                Button {
                    model.send(to: peer)
                } label: {
                    Label(peer.alias + (model.isPaired(peer) ? " (paired)" : ""),
                          systemImage: peer.deviceType == "mobile" ? "ipad.landscape" : "laptopcomputer")
                }
            }
            HStack {
                Button("Refresh") { model.refreshNearby() }
                Button("Cancel") { model.cancelPendingSend() }
            }
        }
    }

    private func chooseFiles() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = true
        panel.prompt = "Send"
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK { model.send(panel.urls) }
    }

    private func receiveFolder() -> URL {
        try? FileManager.default.createDirectory(at: MacFileStore.folder, withIntermediateDirectories: true)
        return MacFileStore.folder
    }
}

private struct OfferView: View {
    let offer: Offer
    @ObservedObject var model: BridgeModel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("\(offer.senderName) wants to send \(offer.files.count) file\(offer.files.count == 1 ? "" : "s") (\(bytes(offer.files.reduce(0) { $0 + $1.size })))")
                .font(.headline).fixedSize(horizontal: false, vertical: true)
            ForEach(Array(offer.files.prefix(8).enumerated()), id: \.offset) { _, file in
                HStack {
                    Text(file.name).lineLimit(1).truncationMode(.middle)
                    Spacer()
                    Text(bytes(file.size)).foregroundStyle(.secondary)
                }.font(.caption)
            }
            if offer.files.count > 8 { Text("and \(offer.files.count - 8) more").font(.caption).foregroundStyle(.secondary) }
            HStack {
                Button("Decline") { model.respond(offer, accept: false) }
                Button("Accept") { model.respond(offer, accept: true) }.keyboardShortcut(.defaultAction)
            }
        }
    }
}

private struct TransferRow: View {
    let transfer: Transfer
    @ObservedObject var model: BridgeModel

    private var active: Bool { [TransferStatus.waiting, .running, .paused].contains(transfer.status) }
    private var total: Int64 { transfer.files.reduce(0) { $0 + $1.size } }
    private var done: Int64 { transfer.files.reduce(0) { $0 + $1.done } }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Image(systemName: transfer.incoming ? "arrow.down.circle" : "arrow.up.circle")
                Text(transfer.peerName).lineLimit(1)
                Spacer()
                Text(summary).font(.caption).foregroundStyle(.secondary)
                if active {
                    Button { model.cancel(transfer) } label: { Image(systemName: "xmark.circle.fill") }
                        .buttonStyle(.borderless).help("Cancel")
                }
            }
            if active { ProgressView(value: Double(done), total: Double(max(total, 1))) }
            if transfer.files.count == 1 {
                fileRow(transfer.files[0])
            } else {
                DisclosureGroup("\(transfer.files.count) files") {
                    ForEach(Array(transfer.files.enumerated()), id: \.offset) { _, file in fileRow(file) }
                }.font(.caption)
            }
        }
    }

    private var summary: String {
        switch transfer.status {
        case .running: return "\(bytes(done)) of \(bytes(total)) · \(bytes(transfer.bytesPerSecond))/s"
        case .paused: return "Reconnecting…"
        case .waiting: return transfer.incoming ? "Waiting" : "Waiting for \(transfer.peerName)"
        case .done: return "Done · \(bytes(total))"
        case .declined: return "Declined"
        case .cancelled: return "Cancelled"
        default: return "Failed"
        }
    }

    private func fileRow(_ file: TransferFile) -> some View {
        HStack {
            Text(file.name).lineLimit(1).truncationMode(.middle)
            Spacer()
            if file.status == .running || file.status == .paused {
                ProgressView(value: Double(file.done), total: Double(max(file.size, 1))).frame(width: 80)
            } else {
                Text(file.status == .done ? bytes(file.size) : file.status.name.lowercased()).foregroundStyle(.secondary)
            }
        }.font(.caption)
    }
}

private func bytes(_ count: Int64) -> String { ByteCountFormatter.string(fromByteCount: count, countStyle: .file) }
