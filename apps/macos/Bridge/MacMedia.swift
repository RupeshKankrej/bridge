import AppKit
import IOKit.hidsystem
import BridgeCore

/// MediaPort on the keyboard's media keys (spec §5.6, ADR 0011). Play/pause, next and previous are posted as the same
/// system-defined events the ⏯ ⏭ ⏮ keys send, so macOS routes them to whatever is playing: a browser tab, Spotify,
/// Music, VLC. macOS doesn't tell non-Apple apps what that is (MediaRemote is private and, since 15.4, Apple-only), so
/// `media.state` carries only the system output volume, which the slider controls.
///
/// Posting needs the Accessibility permission, shared with remote input (MacInput guides the user to it).
final class MacMedia: NSObject, MediaPort {
    private let input: MacInput
    private var onState: ((MediaState) -> Void)?
    private var timer: Timer?

    init(input: MacInput) {
        self.input = input
    }

    func watch(onState: ((MediaState) -> Void)?) {
        self.onState = onState
        timer?.invalidate()
        timer = nil
        guard onState != nil else { return }
        // Only the volume can change under us (keyboard, Control Centre); core drops unchanged states.
        let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in self?.report() }
        RunLoop.main.add(timer, forMode: .common) // keeps firing while the menu-bar popover is open
        self.timer = timer
        report()
    }

    /// False when Accessibility is missing; core then tells the tablet (M2.3's silent failure).
    func command(command: MediaCommand) -> Bool {
        switch command.action {
        // The play key toggles; the Mac can't know which state the player is in, so play and pause send the same key.
        case .play, .pause: return input.mediaKey(NX_KEYTYPE_PLAY)
        // FAST/REWIND, not NEXT/PREVIOUS: they're what Apple keyboards' F9/F7 send, so players treat them as next/prev.
        case .next: return input.mediaKey(NX_KEYTYPE_FAST)
        case .prev: return input.mediaKey(NX_KEYTYPE_REWIND)
        case .volume:
            guard let volume = command.volume?.intValue else { return true }
            run("set volume output volume \(volume)")
            report() // the slider settles on the real value now (spec §7: feedback within 300 ms)
            return true
        default: return true
        }
    }

    private func report() {
        // Standard Additions run inside Bridge: no Apple Event to another app, so no Automation permission.
        let result = run("output volume of (get volume settings)")
        // `missing value` when the output (e.g. some HDMI displays) has no volume control: the slider hides.
        let volume = result?.descriptorType == typeSInt32 ? KotlinInt(int: result!.int32Value) : nil
        onState?(MediaState(app: nil, title: nil, artist: nil, playing: false, position: nil, duration: nil, volume: volume))
    }

    @discardableResult
    private func run(_ source: String) -> NSAppleEventDescriptor? {
        var error: NSDictionary?
        let result = NSAppleScript(source: source)?.executeAndReturnError(&error)
        if let error { log.debug("volume script failed: \(String(describing: error[NSAppleScript.errorNumber]))") }
        return error == nil ? result : nil
    }
}

/// SharePort: links from the tablet open in the default browser; text goes on the clipboard with a notification
/// saying so. Core has already checked the link is http(s). Never logs either.
final class MacShare: NSObject, SharePort {
    private let notifications: MacNotifications

    init(notifications: MacNotifications) {
        self.notifications = notifications
    }

    func open(share: ShareText) -> Bool {
        if let link = share.url, let url = URL(string: link) {
            NSWorkspace.shared.open(url)
        } else if let text = share.text {
            // shortcut: clipboard sync sends this back to the tablet's clipboard too; fine while F2 is on for both.
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(text, forType: .string)
            notifications.notice(title: "Text from your tablet", body: "Copied to the clipboard")
        }
        return true
    }
}
