import AppKit
import Combine
import Network
import ServiceManagement
import CoreImage.CIFilterBuiltins
import SwiftUI
import BridgeCore

@main
struct BridgeApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    // LSUIElement (Info.plist) hides the Dock icon; AppDelegate's status item is the whole UI.
    var body: some Scene { Settings { EmptyView() } }
}

/// The menu-bar icon. An NSStatusItem rather than SwiftUI's MenuBarExtra because files are dropped on
/// the icon, and only AppKit exposes the icon's window to register for drags.
@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate, NSDraggingDestination {
    private let model = BridgeModel()
    private var statusItem: NSStatusItem!
    private let popover = NSPopover()
    private var services: ServicesProvider?
    private var subscriptions: Set<AnyCancellable> = []

    func applicationDidFinishLaunching(_ notification: Notification) {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        let button = statusItem.button!
        button.target = self
        button.action = #selector(togglePopover)
        // The status button's window forwards dragging calls to its delegate (this class).
        button.window?.registerForDraggedTypes([.fileURL])
        button.window?.delegate = self

        let hosting = NSHostingController(rootView: MenuView(model: model))
        hosting.sizingOptions = .preferredContentSize
        popover.contentViewController = hosting
        popover.behavior = .transient

        // @Published emits before the property changes, so use the value passed in, not model.state.
        model.$state.sink { [weak self] state in self?.updateIcon(state) }.store(in: &subscriptions)
        model.onAttention = { [weak self] in self?.showPopover() }
        model.onOffer = { [weak self] offer in self?.notify(offer) }
        model.onSettings = { [weak self] tab in self?.showSettings(tab) }

        // Finder → Services → "Send with Bridge", and "Send to Tablet" for selected text and links (NSServices in Info.plist).
        services = ServicesProvider(model: model)
        NSApp.servicesProvider = services
        NSUpdateDynamicServices()
    }

    /// The icon, plus the tablet's battery next to it once the tablet reports it (spec §5.6).
    private func updateIcon(_ state: BridgeState?) {
        guard let statusItem, let button = statusItem.button else { return }
        let online = state?.paired.first { $0.online }
        button.image = NSImage(systemSymbolName: online != nil ? "link.circle.fill" : "link.circle", accessibilityDescription: "Bridge")
        let battery = online?.battery.map(batteryText)
        statusItem.length = battery == nil ? NSStatusItem.squareLength : NSStatusItem.variableLength
        button.title = battery.map { " " + $0 } ?? ""
        button.imagePosition = .imageLeft
    }

    @objc private func togglePopover() {
        if popover.isShown { popover.performClose(nil) } else { showPopover() }
    }

    private func showPopover() {
        guard let button = statusItem.button, !popover.isShown else { return }
        NSApp.activate(ignoringOtherApps: true)
        popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
    }

    // MARK: Settings window

    private var settingsWindow: NSWindow?

    /// A plain window rather than SwiftUI's Settings scene, which a menu-bar-only app can't reliably bring to the front.
    private func showSettings(_ tab: SettingsTab) {
        popover.performClose(nil)
        model.settingsTab = tab
        if settingsWindow == nil {
            let window = NSWindow(contentViewController: NSHostingController(rootView: SettingsView(model: model)))
            window.title = "Bridge Settings"
            window.styleMask = [.titled, .closable, .miniaturizable, .resizable]
            window.isReleasedWhenClosed = false
            window.center()
            settingsWindow = window
        }
        NSApp.activate(ignoringOtherApps: true)
        settingsWindow?.makeKeyAndOrderFront(nil)
    }

    // MARK: Incoming offers

    private var offerPanel: NSPanel?

    /// A floating card in the top-right corner with Accept / Decline. Not a system notification: macOS
    /// folds notification actions into an "Options" menu and doesn't allow coloured buttons.
    private func notify(_ offer: Offer?) {
        offerPanel?.close()
        offerPanel = nil
        guard let offer, let screen = statusItem.button?.window?.screen ?? NSScreen.main else { return }
        let card = NSHostingView(rootView: OfferView(offer: offer, model: model)
            .padding(16)
            .frame(width: 340)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12)))
        let panel = OfferPanel(contentRect: NSRect(origin: .zero, size: card.fittingSize),
                               styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.contentView = card
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.level = .floating
        // Visible on every Space and over full-screen apps, without taking focus from them.
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        let area = screen.visibleFrame
        panel.setFrameTopLeftPoint(NSPoint(x: area.maxX - panel.frame.width - 12, y: area.maxY - 12))
        panel.orderFrontRegardless()
        offerPanel = panel
        NSSound(named: "Glass")?.play()
    }

    // MARK: Drop files on the icon

    private func fileURLs(_ info: NSDraggingInfo) -> [URL] {
        info.draggingPasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
    }

    func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation { fileURLs(sender).isEmpty ? [] : .copy }

    func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        let urls = fileURLs(sender)
        model.send(urls)
        return !urls.isEmpty
    }
}

/// Borderless panels can't become key by default; this one must, so its buttons take the first click.
private final class OfferPanel: NSPanel {
    override var canBecomeKey: Bool { true }
}

func batteryText(_ b: BatteryState) -> String { "\(b.level)%" + (b.charging ? " ⚡︎" : "") }

/// Target of the Services items. Their titles are fixed in Info.plist, so they can't name the device;
/// for files the popover asks which device unless exactly one paired device is nearby; text goes to every connected one.
final class ServicesProvider: NSObject {
    private let model: BridgeModel

    init(model: BridgeModel) { self.model = model }

    @objc func sendFiles(_ pasteboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        let urls = pasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
        // AppKit calls service handlers on the main thread.
        MainActor.assumeIsolated { model.send(urls) }
    }

    /// "Send to Tablet": selected text, or a link (e.g. from a browser's address field), opens on the tablet (F12).
    @objc func sendText(_ pasteboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        guard let text = pasteboard.string(forType: .string) ?? pasteboard.string(forType: .URL) else { return }
        MainActor.assumeIsolated { model.share(text) }
    }
}

@MainActor
final class BridgeModel: ObservableObject {
    @Published private(set) var state: BridgeState?
    @Published private(set) var files: FilesState?
    @Published private(set) var problem: String?
    /// Files dropped or sent via Services, waiting for the user to pick a device.
    @Published private(set) var pendingSend: [MacOutgoingFile] = []
    @Published private(set) var launchAtLogin = SMAppService.mainApp.status
    /// Opens the popover: a send needs a device picked.
    var onAttention: () -> Void = {}
    /// Called when the pending offer changes (nil once answered or expired).
    var onOffer: (Offer?) -> Void = { _ in }
    /// Opens the settings window on a tab.
    var onSettings: (SettingsTab) -> Void = { _ in }
    @Published var settingsTab: SettingsTab = .general
    @Published private(set) var receiveFolder = MacFileStore.folder
    private let bridge: Bridge
    private let fileTransfer: FileTransfer
    private let activity: NSObjectProtocol
    private let pathMonitor = NWPathMonitor()
    private var subscriptions: Set<AnyCancellable> = []

    var isConnected: Bool { state?.paired.contains { $0.online } ?? false }

    init() {
        CrashLog.install()
        let keyStore = MacKeyStore()
        let network = MacNetwork(identity: keyStore.tlsIdentity)
        let problems = PassthroughSubject<String?, Never>()
        let notifications = MacNotifications { problems.send($0) }
        let input = MacInput { problems.send($0) }
        bridge = Bridge(
            name: Host.current().localizedName ?? "Mac",
            platform: .macos,
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0",
            keyStore: keyStore,
            transport: network,
            discovery: network,
            clipboard: MacClipboard(),
            notifications: notifications,
            input: input,
            media: MacMedia(input: input),
            share: MacShare(notifications: notifications)
        )
        // After Bridge, which creates the identity on first launch.
        fileTransfer = FileTransfer(
            name: Host.current().localizedName ?? "Mac",
            platform: .macos,
            deviceModel: "Mac",
            keyStore: keyStore,
            streams: MacStreams(identity: keyStore.tlsIdentity) { problems.send($0) },
            multicast: MacMulticast(),
            store: MacFileStore()
        )
        // App Nap would stretch the 15 s heartbeat timers of a windowless app until the tablet drops us.
        activity = ProcessInfo.processInfo.beginActivity(options: .userInitiatedAllowingIdleSystemSleep, reason: "Keep the tablet session alive")
        bridge.observe { [weak self] state in
            // Core delivers on Dispatchers.Main, which is the main queue.
            MainActor.assumeIsolated { self?.state = state }
        }
        bridge.start()
        fileTransfer.observe { [weak self] files in
            MainActor.assumeIsolated {
                guard let self else { return }
                let changed = files.offer?.id != self.files?.offer?.id
                self.files = files
                if changed { self.onOffer(files.offer) }
            }
        }
        fileTransfer.start()
        watchWakeAndNetwork()
        enableLaunchAtLoginOnFirstRun()
        problems.receive(on: DispatchQueue.main).sink { [weak self] in self?.problem = $0 }.store(in: &subscriptions)
    }

    /// Sessions from before sleep or on the old network look open but are dead; core pings them and drops the silent ones
    /// so the tablet's redial is accepted at once. The Mac never dials, so this is all "reconnect" means on this side.
    private func watchWakeAndNetwork() {
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.bridge.reconnect() }
        }
        // Fires on every interface change (Wi-Fi network switch, Ethernet plugged in) and once at start.
        pathMonitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            MainActor.assumeIsolated { self?.bridge.reconnect() }
        }
        pathMonitor.start(queue: .main)
    }

    /// Spec §7: launch at login defaults to on. Registered once; after that the menu toggle is the user's choice.
    private func enableLaunchAtLoginOnFirstRun() {
        let key = "launchAtLoginDefaulted"
        guard !UserDefaults.standard.bool(forKey: key) else { return }
        UserDefaults.standard.set(true, forKey: key)
        setLaunchAtLogin(true)
    }

    func setLaunchAtLogin(_ on: Bool) {
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        } catch {
            problem = "Couldn't change Open at Login: \(error.localizedDescription)"
        }
        launchAtLogin = SMAppService.mainApp.status
    }

    func isPaired(_ peer: LocalSendPeer) -> Bool { state?.paired.contains { $0.fingerprint == peer.fingerprint } ?? false }

    /// Sends straight to the paired device if exactly one is nearby; otherwise asks in the popover.
    func send(_ urls: [URL]) {
        let outgoing = MacOutgoingFile.expand(urls)
        guard !outgoing.isEmpty else { return }
        let paired = (files?.nearby ?? []).filter(isPaired)
        if paired.count == 1 {
            fileTransfer.send(peer: paired[0], files: outgoing)
        } else {
            pendingSend = outgoing
        }
        onAttention()
    }

    func send(to peer: LocalSendPeer) {
        fileTransfer.send(peer: peer, files: pendingSend)
        pendingSend = []
    }

    /// Services "Send to Tablet". Offline: say so in the popover (spec §7) rather than dropping it silently.
    func share(_ text: String) {
        guard isConnected else {
            problem = "The tablet isn't connected, so the text wasn't sent"
            return onAttention()
        }
        bridge.share(text: text)
    }

    func cancelPendingSend() { pendingSend = [] }
    func refreshNearby() { fileTransfer.refresh() }
    func respond(offerId: String, accept: Bool) { fileTransfer.respond(offerId: offerId, accept: accept) }
    func cancel(_ transfer: Transfer) { fileTransfer.cancel(transferId: transfer.id) }

    func openPairing() {
        guard let ip = lanIPv4Address() else {
            problem = "No Wi-Fi or Ethernet address; connect to the same network as the tablet"
            return
        }
        problem = nil
        bridge.openPairing(host: ip)
    }

    func closePairing() { bridge.closePairing() }
    func reconnect() { bridge.reconnect() }
    func openSettings(_ tab: SettingsTab = .general) { onSettings(tab) }

    /// nil goes back to ~/Downloads/Bridge.
    func setReceiveFolder(_ url: URL?) {
        MacFileStore.folder = url ?? MacFileStore.defaultFolder
        receiveFolder = MacFileStore.folder
    }
    func cancelPairing() { bridge.rejectPairing() }
    func forget(_ fingerprint: String) { bridge.unpair(fingerprint: fingerprint) }
}

/// First IPv4 address on a Wi-Fi/Ethernet interface (en*), skipping VPN tunnels and loopback.
func lanIPv4Address() -> String? {
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
