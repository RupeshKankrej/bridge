import AppKit
import BridgeCore

/// ClipboardPort on NSPasteboard. macOS has no "clipboard changed" event, so we poll `changeCount`
/// (a cheap integer that goes up on every copy) every 250 ms on the main run loop (spec §5.2).
/// Never logs clipboard contents.
final class MacClipboard: NSObject, ClipboardPort {
    /// Marks items Bridge wrote so the poller doesn't send them back.
    private static let origin = NSPasteboard.PasteboardType("dev.bridge.origin")
    /// Password managers add this type to say "don't sync me" (nspasteboard.org convention).
    private static let concealed = NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")

    private let pasteboard = NSPasteboard.general
    private var lastChangeCount = NSPasteboard.general.changeCount
    private var timer: Timer?

    func watch(onLocalChange: @escaping (String) -> Void) {
        let timer = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in
            guard let self, pasteboard.changeCount != lastChangeCount else { return }
            lastChangeCount = pasteboard.changeCount
            let types = pasteboard.types ?? []
            guard !types.contains(Self.origin), !types.contains(Self.concealed),
                  let text = pasteboard.string(forType: .string) else { return }
            onLocalChange(text)
        }
        // .common so it keeps firing while the menu-bar window is open (that runs the loop in tracking mode).
        RunLoop.main.add(timer, forMode: .common)
        self.timer = timer
    }

    func write(text: String) {
        // Core calls this on the main queue, the same thread the timer runs on, so no locking.
        pasteboard.clearContents()
        pasteboard.declareTypes([.string, Self.origin], owner: nil)
        pasteboard.setString(text, forType: .string)
        pasteboard.setData(Data(), forType: Self.origin)
    }
}
