import AppKit
import CoreGraphics
import BridgeCore

/// InputPort on CGEvent (spec §5.5): moves, clicks, scrolls and types what the tablet sends.
///
/// Posting events into other apps' input stream needs the Accessibility permission (System Settings → Privacy &
/// Security → Accessibility). Without it `CGEvent.post` silently does nothing, so we check first with
/// `CGPreflightPostEventAccess` and return false; core then tells the tablet. The first refusal opens a guided alert.
/// Dev builds: the grant is tied to the code signature, so after a rebuild that re-signs Bridge differently, remove it
/// from that list and add it again.
/// Never logs typed text.
final class MacInput: NSObject, InputPort {
    private let onProblem: (String?) -> Void
    /// `.hidSystemState` makes the events look like they came from real hardware (merged with the physical modifier state).
    private let source = CGEventSource(stateID: .hidSystemState)
    private var allowed = false
    private var lastCheck = Date.distantPast
    private var guided = false
    /// Held buttons as of the previous message; presses and releases are the difference (see InputPointer).
    private var buttons: Int32 = 0
    /// Scroll events take whole pixels; fractions carry over to the next message.
    private var scrollRest = (x: 0.0, y: 0.0)
    private var lastPress = (time: 0.0, at: CGPoint.zero, button: CGMouseButton.left, count: Int64(0))

    init(onProblem: @escaping (String?) -> Void) {
        self.onProblem = onProblem
    }

    func pointer(event e: InputPointer) -> Bool {
        guard mayPost() else { return false }
        var at = CGEvent(source: nil)?.location ?? .zero // current cursor, global top-left coordinates
        if e.dx != 0 || e.dy != 0 {
            at = onScreen(CGPoint(x: at.x + e.dx, y: at.y + e.dy), from: at)
            let type: CGEventType = buttons & MessagesKt.BUTTON_LEFT != 0 ? .leftMouseDragged
                : buttons & MessagesKt.BUTTON_RIGHT != 0 ? .rightMouseDragged : .mouseMoved
            let move = CGEvent(mouseEventSource: source, mouseType: type, mouseCursorPosition: at, mouseButton: .left)
            // Apps that read relative motion (games, some 3D views) use these fields, not the position.
            move?.setIntegerValueField(.mouseEventDeltaX, value: Int64(e.dx.rounded()))
            move?.setIntegerValueField(.mouseEventDeltaY, value: Int64(e.dy.rounded()))
            move?.post(tap: .cghidEventTap)
        }
        if e.buttons != buttons {
            press(.left, bit: MessagesKt.BUTTON_LEFT, held: e.buttons, at: at, down: .leftMouseDown, up: .leftMouseUp)
            press(.right, bit: MessagesKt.BUTTON_RIGHT, held: e.buttons, at: at, down: .rightMouseDown, up: .rightMouseUp)
            buttons = e.buttons
        }
        if e.scrollX != 0 || e.scrollY != 0 { scroll(e.scrollX, e.scrollY) }
        // Pinch: there's no public API for trackpad magnify events, so a zoom step is ⌘= / ⌘− (ADR 0010).
        if let g = e.gesture { tap(CGKeyCode(g == .zoomIn ? MacKey.shared.EQUAL : MacKey.shared.MINUS), flags: .maskCommand) }
        return true
    }

    func key(event e: InputKey) -> Bool {
        guard mayPost() else { return false }
        if let code = e.keyCode {
            tap(CGKeyCode(code.int32Value), flags: flags(e.modifiers))
        } else if let text = e.text {
            type(text)
        }
        return true
    }

    // MARK: Events

    /// One press and release of a media key ([key] is an `NX_KEYTYPE_*`), as the keyboard sends it: a system-defined
    /// event, subtype 8, with the key in the high 16 bits of data1 and 0xA (down) / 0xB (up) in the next byte.
    func mediaKey(_ key: Int32) -> Bool {
        guard mayPost() else { return false }
        for state in [0xA, 0xB] {
            let event = NSEvent.otherEvent(with: .systemDefined, location: .zero, modifierFlags: NSEvent.ModifierFlags(rawValue: UInt(state << 8)),
                                           timestamp: 0, windowNumber: 0, context: nil, subtype: 8,
                                           data1: Int(key) << 16 | state << 8, data2: -1)
            event?.cgEvent?.post(tap: .cghidEventTap)
        }
        return true
    }

    private func press(_ button: CGMouseButton, bit: Int32, held: Int32, at: CGPoint, down: CGEventType, up: CGEventType) {
        guard (held ^ buttons) & bit != 0 else { return }
        let pressing = held & bit != 0
        if pressing {
            // Without a click count, two quick taps are two single clicks: no double-click to open in Finder.
            let now = ProcessInfo.processInfo.systemUptime
            let again = button == lastPress.button && now - lastPress.time < NSEvent.doubleClickInterval
                && hypot(at.x - lastPress.at.x, at.y - lastPress.at.y) < 5
            lastPress = (now, at, button, again ? lastPress.count + 1 : 1)
        }
        let event = CGEvent(mouseEventSource: source, mouseType: pressing ? down : up, mouseCursorPosition: at, mouseButton: button)
        event?.setIntegerValueField(.mouseEventClickState, value: lastPress.count)
        event?.post(tap: .cghidEventTap)
    }

    /// Positive wheel values scroll up / left, i.e. move the content down / right, which is what scrollX/Y mean.
    /// Injected events aren't flipped by the "Natural scrolling" setting, so content always follows the fingers.
    private func scroll(_ dx: Double, _ dy: Double) {
        scrollRest.x += dx
        scrollRest.y += dy
        let x = Int32(scrollRest.x.rounded(.towardZero)), y = Int32(scrollRest.y.rounded(.towardZero))
        scrollRest.x -= Double(x)
        scrollRest.y -= Double(y)
        guard x != 0 || y != 0 else { return }
        CGEvent(scrollWheelEvent2Source: source, units: .pixel, wheelCount: 2, wheel1: y, wheel2: x, wheel3: 0)?.post(tap: .cghidEventTap)
    }

    private func tap(_ code: CGKeyCode, flags: CGEventFlags) {
        for down in [true, false] {
            let event = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: down)
            event?.flags = flags
            event?.post(tap: .cghidEventTap)
        }
    }

    /// Text goes in as Unicode strings on a key event, so any character works whatever the Mac's keyboard layout.
    /// A key event carries at most 20 UTF-16 units; chunks never split a character.
    private func type(_ text: String) {
        var chunk: [UniChar] = []
        for character in text {
            let units = Array(String(character).utf16)
            if chunk.count + units.count > 20 {
                post(chunk)
                chunk = []
            }
            chunk += units
        }
        if !chunk.isEmpty { post(chunk) }
    }

    private func post(_ units: [UniChar]) {
        for down in [true, false] {
            let event = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: down)
            event?.flags = [] // or a held physical modifier would turn the text into shortcuts
            event?.keyboardSetUnicodeString(stringLength: units.count, unicodeString: units)
            event?.post(tap: .cghidEventTap)
        }
    }

    private func flags(_ modifiers: Int32) -> CGEventFlags {
        var f: CGEventFlags = []
        if modifiers & MessagesKt.MOD_SHIFT != 0 { f.insert(.maskShift) }
        if modifiers & MessagesKt.MOD_CONTROL != 0 { f.insert(.maskControl) }
        if modifiers & MessagesKt.MOD_OPTION != 0 { f.insert(.maskAlternate) }
        if modifiers & MessagesKt.MOD_COMMAND != 0 { f.insert(.maskCommand) }
        return f
    }

    /// Keeps the pointer on a display: a point off every screen stays on the edge of the one it was on.
    private func onScreen(_ p: CGPoint, from old: CGPoint) -> CGPoint {
        var count: UInt32 = 0
        if CGGetDisplaysWithPoint(p, 0, nil, &count) == .success, count > 0 { return p }
        var display = CGMainDisplayID()
        CGGetDisplaysWithPoint(old, 1, &display, &count)
        let b = CGDisplayBounds(display)
        return CGPoint(x: min(max(p.x, b.minX), b.maxX - 1), y: min(max(p.y, b.minY), b.maxY - 1))
    }

    // MARK: Accessibility

    /// Granted is remembered; while missing, the TCC lookup runs at most once a second, not 120 times.
    private func mayPost() -> Bool {
        if allowed { return true }
        guard Date().timeIntervalSince(lastCheck) >= 1 else { return false }
        lastCheck = Date()
        allowed = CGPreflightPostEventAccess()
        if allowed {
            onProblem(nil)
        } else {
            onProblem("Trackpad and media keys from the tablet need Accessibility: turn on Bridge in System Settings → Privacy & Security → Accessibility")
            if !guided {
                guided = true
                // Async: core called us mid-dispatch, and a modal alert shouldn't run inside that.
                DispatchQueue.main.async { self.guide() }
            }
        }
        return allowed
    }

    /// Spec §6: Accessibility is asked for at the first remote-input use, with a one-line why (spec §7).
    private func guide() {
        NSApp.activate(ignoringOtherApps: true) // a menu-bar app's alert would otherwise open behind the front app
        let alert = NSAlert()
        alert.messageText = "Let the tablet control this Mac?"
        alert.informativeText = """
            To use your tablet as a trackpad and keyboard, Bridge needs the Accessibility permission.

            In System Settings → Privacy & Security → Accessibility, turn on Bridge. It takes effect at once; no restart needed.
            """
        alert.addButton(withTitle: "Open System Settings")
        alert.addButton(withTitle: "Not Now")
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        // Adds Bridge to the list (switched off) so the user only has to flip it.
        CGRequestPostEventAccess()
        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!)
    }
}
