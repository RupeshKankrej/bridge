# ADR 0011 — Media controls, battery and share-to-device

**Status:** accepted · 2026-11-07

## Context
M2.3 (spec §5.6, F7 + F12) adds now-playing control of the Mac's music from the tablet, the tablet's battery in the Mac's
menu bar, and sending links and text either way. Spec §4 listed the four message types with loose payloads; the spec
named `MediaRemote` with an AppleScript fallback for the Mac side, which conflicts with CLAUDE.md hard rule 7 (no private
Apple APIs in Phase 1 or 2).

## Decision
- **AppleScript only, no MediaRemote.** Agreed with Rupesh on 2026-11-07. Besides rule 7, macOS 15.4+ only gives
  `MediaRemote` now-playing data to Apple-entitled processes, so on macOS 26 it would return nothing. AppleScript covers
  Spotify and Music, which is the gate; browsers and other players aren't covered.
- **The Mac polls only while watched.** `media.command` gains `watch` / `unwatch`. The tablet sends `watch` while Bridge
  is on screen (activity resumed) with media controls switched on, and `unwatch` when it leaves; the Mac polls once a
  second only while some connection watches. That keeps the cost (one Apple Event per running player per second) to
  the minutes someone looks at the card, and means the Mac never asks for the Automation permission unless the tablet
  uses media controls. Actions on the existing message, rather than a new type, keep spec §4's table as it was.
- **Position isn't streamed.** Core on the Mac drops a `media.state` whose only change is the position advancing as
  expected (within 2 s); the tablet advances it locally from the arrival time. Any other change, or a seek, is sent.
  After a command the Mac polls at once so the card updates within the 300 ms of spec §7.
- **Automation permission asked off the main thread.** The first Apple Event to an app blocks until the user answers the
  consent prompt, which would stall core (it runs on the main queue) and its heartbeat. The Mac asks once per player with
  `AEDeterminePermissionToAutomateTarget` on a background queue and polls only after a yes; a no names the cause.
  The hardened runtime needs `com.apple.security.automation.apple-events` (new `Bridge.entitlements`).
- **Battery** goes out on connect, every 5 minutes and on charger plug/unplug while the switch is on; `charging` means a
  charger is connected. The Mac shows the level of the connected tablet beside the menu-bar icon and in the popover.
- **share.text** carries exactly one of `url` or `text`. A shared string that is one http(s) URL (trimmed) is a `url`;
  anything else is `text`. Receivers refuse other schemes (`file:`, `javascript:`, app schemes) with `invalid_message`,
  so a peer can never make the other side open something other than a web page. Text is capped at 1 MB like the
  clipboard. The Mac opens links in the default browser and puts text on the clipboard with a notice; the tablet posts a
  notification (Android 10+ forbids starting activities from the background) whose tap opens the link or the share sheet.
  A tablet with receiving switched off answers `share_off`, which the Mac shows.
- **New port `SharePort`** for opening shared content; `MediaPort` is the one spec §6 already named.
- **Three switches on the tablet** (media controls, battery, links and text), all off by default as spec §7 says for
  everything but clipboard. The Mac has no switches of its own: what it does follows what the tablet sends.

## Consequences
- Only Spotify and Music. Safari/Chrome video, Podcasts and others show "Nothing playing".
- The Services item's title is fixed in Info.plist ("Send to Tablet"), so it can't carry the device's name; text goes to
  every connected device.
- Text shared from the tablet lands on the Mac's clipboard, so clipboard sync sends it back to the tablet's clipboard.
- A shared sentence that contains a link (not just the link) arrives as text, not as an opened page.
- Turning battery reporting off leaves the last level on the Mac until the tablet next disconnects.
- Dev builds re-signed differently may lose the Automation grant, like Accessibility (docs/testing.md).
