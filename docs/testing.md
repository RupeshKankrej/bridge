# Testing

## Automated
- `./gradlew :core:allTests` — codec round-trips, session state machine, pairing, LocalSend parsing, contract tests over localhost. Run before every commit.
- `./gradlew :core:localSendInteropTest` — exchanges files with the official LocalSend build in both directions. Needs LocalSend CLI on PATH.

## Device checklist (run on the MacBook Air M5 + OnePlus Pad 3 before each milestone is marked done)
- [ ] Fresh install on both devices; pairing via QR completes with matching 6-digit codes
- [ ] Clipboard text Mac → tablet within 150 ms
- [ ] Clipboard text tablet → Mac via quick-settings tile
- [ ] Send 1 file, 10 files, 1 folder from each side; progress and cancel work
- [ ] 2 GB file transfers at ≥ 40 MB/s and resumes after a 10 s Wi-Fi drop
- [ ] Official LocalSend app (any platform) can send to and receive from both Bridge apps
- [ ] Both apps reconnect automatically after Mac sleep/wake and tablet screen-off for 30 min
- [ ] Reconnect after switching Wi-Fi networks and back
- [ ] Unpair from one side; the other side shows disconnected and refuses requests
- [ ] (Phase 2+) notifications, trackpad, media, battery
- [ ] (Phase 3+) 30-minute mirroring session each direction without disconnect; latency overlay < 100 ms

## M1.2 gate (pairing)
1. `./gradlew :core:assembleBridgeCoreDebugXCFramework`, then build and run the Mac app (`apps/macos`). Allow Local
   Network access when macOS asks. The menu-bar icon appears and the tablet shows up under "Nearby" once its app runs.
2. `./gradlew :apps:android:installDebug`, then open Bridge on the Pad 3 and allow notifications.
3. Mac: menu bar → Pair a device…. Tablet: Scan QR code. Both show the same 6-digit code; tap Confirm on the tablet.
- [ ] Both show "Paired with <name>"
- [ ] Leave both idle 10 minutes (tablet screen on, or Bridge exempted from battery optimisation): still connected
- [ ] Scan a QR, then change one digit by scanning a stale QR: refused, and the Mac shows a new code
- [ ] Forget on the Mac: within a few seconds the tablet shows it is no longer trusted

## M1.3 gate (clipboard text)
Rebuild both apps as in the M1.2 gate; the devices stay paired.
- [ ] Copy text on the Mac, paste on the tablet: arrives within 150 ms of the Mac noticing the copy (the 250 ms poll adds up to 250 ms on top; see ADR 0006)
- [ ] Copy text in any tablet app, pull down quick settings, tap "Send clipboard" (add the tile once via the pencil icon): toast "Clipboard sent", paste on the Mac
- [ ] Same via the "Send clipboard" action on the "Bridge is running" notification
- [ ] With Bridge open on the tablet, copy text in its window (e.g. long-press a field): reaches the Mac without the tile
- [ ] Text received on either side does not bounce back (copy on the Mac once; the tablet's toast/clipboard shows no second change, and the Mac's pasteboard isn't rewritten)
- [ ] Copy a password from a password manager on the Mac (1Password, Passwords app): not sent
- [ ] Copy > 1 MB of text on the Mac: not sent; the menu shows "over 1 MB"
- [ ] Turn tablet Wi-Fi off, copy on the Mac, turn Wi-Fi on: after reconnect the tablet has the new text

## One-time device checks
- [ ] Android version reported by the Pad 3 in Settings → About
- [ ] `packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)` on the Pad 3 (needed for Tier B AirDrop)
- [ ] `Presentation` display support on the Pad 3 (needed for F10)
