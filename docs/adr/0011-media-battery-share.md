# ADR 0011 — Media controls, battery and share-to-device

**Status:** accepted · 2026-11-07

## Context
M2.3 (spec §5.6, F7 + F12) adds now-playing control of the Mac's music from the tablet, the tablet's battery in the Mac's
menu bar, and sending links and text either way. Spec §4 listed the four message types with loose payloads; the spec
named `MediaRemote` with an AppleScript fallback for the Mac side, which conflicts with CLAUDE.md hard rule 7 (no private
Apple APIs in Phase 1 or 2).

## Decision
- **Media keys, no MediaRemote, no player AppleScript.** Agreed with Rupesh on 2026-11-07. MediaRemote is out under
  rule 7, and macOS 15.4+ only gives its now-playing data to Apple-entitled processes anyway. AppleScript for Spotify and
  Music was built first, but Rupesh plays neither, so it was replaced: play/pause, next and prev are posted as the
  keyboard's media-key events (system-defined, subtype 8; `NX_KEYTYPE_PLAY`, `FAST`, `REWIND` as Apple keyboards send),
  which macOS routes to whatever is playing, browser tabs included. Posting reuses the Accessibility permission and
  guided prompt from remote input (ADR 0010). The volume slider sets the system output volume through Standard
  Additions, which run inside Bridge and need no Automation permission, so the Apple Events entitlement was dropped again.
  The cost: the Mac can't tell what's playing or whether it's paused, so the tablet shows one ⏯ toggle and no title.
  `media.state` keeps its title/artist/position fields for a future sender (iOS) that knows them.
- **The Mac reports only while watched.** `media.command` gains `watch` / `unwatch`. The tablet sends `watch` while
  Bridge is on screen (activity resumed) with media controls switched on, and `unwatch` when it leaves; the Mac reads
  the volume once a second only while some connection watches. Actions on the existing message, rather than a new type,
  keep spec §4's table as it was.
- **Unchanged states aren't resent.** Core on the Mac drops a `media.state` equal to the last one, and one whose only
  change is a position advancing as expected (within 2 s; the receiver advances it from the arrival time).
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
- No title, artist, progress or play state on the tablet. Media keys go to the app macOS considers "now playing"; with
  nothing playing, ⏯ usually starts Music.
- The Services item's title is fixed in Info.plist ("Send to Tablet"), so it can't carry the device's name; text goes to
  every connected device.
- Text shared from the tablet lands on the Mac's clipboard, so clipboard sync sends it back to the tablet's clipboard.
- A shared sentence that contains a link (not just the link) arrives as text, not as an opened page.
- Turning battery reporting off leaves the last level on the Mac until the tablet next disconnects.
- Dev builds re-signed differently lose the Accessibility and Local Network grants (docs/testing.md).
