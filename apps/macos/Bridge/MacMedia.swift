import AppKit
import BridgeCore

/// MediaPort on AppleScript for Spotify and Music (spec §5.6, ADR 0011). MediaRemote, the private framework behind
/// the system Now Playing widget, is off limits in Phase 2 (hard rule 7) and since macOS 15.4 no longer tells
/// non-Apple apps what's playing anyway.
///
/// Core starts polling only while the tablet has Bridge on screen with media controls on, so the 1 s poll on the main
/// thread costs nothing the rest of the day. Each poll is one Apple Event per running player (a few ms).
///
/// Apple Events to another app need the Automation permission. Asking blocks until the user answers, so it's done
/// once per player on a background queue with `AEDeterminePermissionToAutomateTarget`; polling starts after a yes.
final class MacMedia: NSObject, MediaPort {
    private struct Player {
        let id: String
        let name: String
        /// Spotify reports duration in milliseconds, Music in seconds.
        let msDuration: Bool
    }

    private static let players = [
        Player(id: "com.spotify.client", name: "Spotify", msDuration: true),
        Player(id: "com.apple.Music", name: "Music", msDuration: false),
    ]

    private let onProblem: (String?) -> Void
    private var onState: ((MediaState) -> Void)?
    private var timer: Timer?
    /// Compiled once; nil if the player isn't installed (its terms can't be compiled).
    private var scripts: [String: NSAppleScript?] = [:]
    private var permission: [String: OSStatus] = [:]
    private var asking: Set<String> = []
    /// The player last reported, so commands go to it.
    private var current: Player?

    init(onProblem: @escaping (String?) -> Void) {
        self.onProblem = onProblem
    }

    func watch(onState: ((MediaState) -> Void)?) {
        self.onState = onState
        timer?.invalidate()
        timer = nil
        guard onState != nil else { return }
        let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in self?.poll() }
        RunLoop.main.add(timer, forMode: .common) // keeps firing while the menu-bar popover is open
        self.timer = timer
        poll()
    }

    func command(command: MediaCommand) {
        guard let player = current else { return }
        let verb: String
        switch command.action {
        case .play: verb = "play"
        case .pause: verb = "pause"
        case .next: verb = "next track"
        case .prev: verb = "previous track"
        case .volume:
            guard let volume = command.volume?.intValue else { return }
            verb = "set sound volume to \(volume)"
        default: return
        }
        var error: NSDictionary?
        NSAppleScript(source: "tell application id \"\(player.id)\" to \(verb)")?.executeAndReturnError(&error)
        if let error { log.debug("media command failed: \(error)") }
        poll() // the tablet sees the result now, not up to a second later (spec §7: feedback within 300 ms)
    }

    private func poll() {
        guard let onState else { return }
        var paused: (Player, MediaState)?
        for player in Self.players where isRunning(player) && mayAutomate(player) {
            guard let state = read(player) else { continue }
            if state.playing {
                current = player
                return onState(state)
            }
            if paused == nil || player.id == current?.id { paused = (player, state) }
        }
        current = paused?.0
        onState(paused?.1 ?? MediaState(app: nil, title: nil, artist: nil, playing: false, position: nil, duration: nil, volume: nil))
    }

    private func isRunning(_ player: Player) -> Bool {
        !NSRunningApplication.runningApplications(withBundleIdentifier: player.id).isEmpty
    }

    /// True once the user allowed it. The first call asks in the background and returns false meanwhile.
    private func mayAutomate(_ player: Player) -> Bool {
        if let status = permission[player.id] { return status == noErr }
        guard !asking.contains(player.id) else { return false }
        asking.insert(player.id)
        let target = NSAppleEventDescriptor(bundleIdentifier: player.id)
        DispatchQueue.global(qos: .userInitiated).async { [weak self, onProblem] in
            let status = AEDeterminePermissionToAutomateTarget(target.aeDesc, typeWildCard, typeWildCard, true)
            DispatchQueue.main.async {
                self?.asking.remove(player.id)
                // -600: the player quit before we asked; ask again next time it runs.
                guard status != procNotFound else { return }
                self?.permission[player.id] = status
                if status == errAEEventNotPermitted {
                    onProblem("Allow Bridge to control \(player.name) in System Settings → Privacy & Security → Automation")
                }
            }
        }
        return false
    }

    /// One Apple Event. The `is running` check is in the script too, so a player that just quit isn't relaunched.
    private func read(_ player: Player) -> MediaState? {
        let compiled = scripts[player.id] ?? {
            let script = NSAppleScript(source: """
                if application id "\(player.id)" is running then
                    with timeout of 2 seconds
                        tell application id "\(player.id)"
                            if player state is stopped then return {"stopped"}
                            return {player state as text, name of current track, artist of current track, ¬
                                player position, duration of current track, sound volume}
                        end tell
                    end timeout
                end if
                return {"stopped"}
                """)
            let compiled = script?.compileAndReturnError(nil) == true ? script : nil
            if compiled == nil { log.debug("\(player.name) script doesn't compile; not installed?") }
            scripts[player.id] = .some(compiled) // remembered, so a missing player isn't recompiled every second
            return compiled
        }()
        guard let compiled else { return nil }
        var error: NSDictionary?
        let result = compiled.executeAndReturnError(&error)
        if let error {
            log.debug("\(player.name) poll failed: \(String(describing: error[NSAppleScript.errorNumber]))")
            return nil
        }
        guard result.numberOfItems == 6 else { return MediaState(app: player.name, title: nil, artist: nil, playing: false, position: nil, duration: nil, volume: nil) }
        let duration = result.atIndex(5)!.doubleValue / (player.msDuration ? 1000 : 1)
        return MediaState(
            app: player.name,
            title: result.atIndex(2)?.stringValue,
            artist: result.atIndex(3)?.stringValue,
            playing: result.atIndex(1)?.stringValue == "playing",
            position: KotlinDouble(double: max(0, result.atIndex(4)!.doubleValue)),
            duration: KotlinDouble(double: max(0, duration)),
            volume: KotlinInt(int: result.atIndex(6)!.int32Value)
        )
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
