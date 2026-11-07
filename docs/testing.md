# Testing

## Automated
- `./gradlew :core:allTests` — codec round-trips, session state machine, pairing, LocalSend parsing, contract tests over localhost. Run before every commit.
- `./gradlew :core:localSendInteropTest` — runs `core` with JDK TLS adapters as "Bridge interop" on port 53321 (paired port 53323) and exchanges
  files with the official LocalSend app on any device on the same Wi-Fi: send it any file from LocalSend, then accept
  `interop.bin` (20 MB) in LocalSend. Waits up to 3 minutes for each step. Received files land in `core/build/interop/`.
  Not part of `allTests`. If the Mac running it also runs LocalSend, both share UDP 53317 for multicast; use another device if
  "Bridge interop" never appears in LocalSend.

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
- [x] Copy text on the Mac, paste on the tablet: arrives within 150 ms of the Mac noticing the copy (the 250 ms poll adds up to 250 ms on top; see ADR 0006)
- [x] Copy text in any tablet app, pull down quick settings, tap "Send clipboard" (add the tile once via the pencil icon): toast "Clipboard sent", paste on the Mac
- [x] Same via the "Send clipboard" action on the "Bridge is running" notification
- [x] With Bridge open on the tablet, copy text in its window (e.g. long-press a field): reaches the Mac without the tile
- [x] Text received on either side does not bounce back (copy on the Mac once; the tablet's toast/clipboard shows no second change, and the Mac's pasteboard isn't rewritten)
- [ ] Copy a password from a password manager on the Mac (1Password, Passwords app): not sent — **fails**, known issue in STATUS.md
- [x] Copy > 1 MB of text on the Mac: not sent; the menu shows "over 1 MB"
- [x] Turn tablet Wi-Fi off, copy on the Mac, turn Wi-Fi on: after reconnect the tablet has the new text

## M1.4b checks (Mac, before the M1.4 gate)
Rebuild core (`./gradlew :core:assembleBridgeCoreDebugXCFramework`) and the Mac app. After a rebuild macOS may ask twice for
Keychain access (certificate, then private key): Always Allow. Quit LocalSend.app on the Mac first; it holds port 53317.
- [x] `lsof` shows Bridge listening on TCP 53317, 53318, 53319 and UDP 53317
- [x] `curl -sk https://127.0.0.1:53317/api/localsend/v2/info` returns LocalSend fields; 53319 refuses curl (no certificate)
- [x] A curl offer opens the menu with Accept / Decline; after Accept a 1 GB upload lands in `~/Downloads/Bridge` (~340 MB/s on localhost)
- [x] Official LocalSend (Android, v2.2) on the Pad 3 → Mac: the Accept / Decline card appears top-right, file lands in `~/Downloads/Bridge`
- [x] Drag a file onto the menu-bar icon → pick the tablet's LocalSend → accepted there and received
- [x] Drag a folder onto the menu-bar icon: structure kept on the receiver
- [x] Finder → right-click → Services → "Send with Bridge" (first time: run the app once, then `/System/Library/CoreServices/pbs -update` or log out/in)
- [x] Cancel from either side stops the transfer on both (Mac shows "Cancelled by <device>" when the tablet cancels)

Troubleshooting seen on the Pad 3: a failed transfer shows its reason in red in the list. "answered HTTP 500" from
LocalSend means the receiving LocalSend can't write to its save folder: open LocalSend → Settings → Receive and grant
storage access.

## M1.4c checks (Android)
Force-stop LocalSend on the tablet first: it holds port 53317 (Bridge says so on its main screen).
- [x] Tablet → Mac, share sheet (Files → Share → Bridge): goes straight to the paired Mac, no prompt; 1 GB intact
- [x] Mac → tablet, drag onto the menu-bar icon: no prompt on the tablet; 2 GB intact in Download/Bridge
- [x] Official LocalSend (LocalSend.app on the Mac, Bridge for Mac quit) ↔ tablet Bridge, both ways: Accept / Decline dialog on the tablet
- [x] 10 s Wi-Fi drop on the tablet mid-transfer (`adb shell svc wifi disable; sleep 10; svc wifi enable`): 2 GB Mac → tablet completed intact
- [ ] Throughput ≥ 40 MB/s: **measure the network first** (below). On 2026-11-07 the home Wi-Fi gave the Pad 3 a 432/540 Mbps
      link and raw TCP managed only ~8 MB/s tablet → Mac and ~7 MB/s Mac → tablet; Bridge reached 5–10 and 10–20 MB/s, i.e. the
      network's limit. Re-run the gate with the Mac on Ethernet (one wireless hop instead of two) and the tablet near an 80/160 MHz AP.

Raw TCP baseline, no Bridge involved (Python receiver on the Mac times first to last byte):
`python3 rx.py 5002` on the Mac, then `adb shell "toybox nc <mac-ip> 5002 < /sdcard/Download/<big file>"`.
The scripts are in the M1.4c PR description.

## M1.5 gate (stays connected; Phase 1 release v0.1.0)
Rebuild core (`./gradlew :core:assembleBridgeCoreDebugXCFramework`), the Mac app, and `./gradlew :apps:android:installDebug`.
The devices stay paired. Then run **every item of the device checklist above** (throughput and the password-manager skip are
accepted known issues for v0.1.0, agreed 2026-11-07), plus:
- [x] Mac: first launch of this build turns on "Open at Login" in the menu (System Settings → General → Login Items lists Bridge);
      log out and in: Bridge is in the menu bar and the tablet reconnects without opening anything on it
- [x] Tablet: home screen shows "Allow background use" until granted; the system dialog grants it and the card disappears
- [x] Mac sleep (close the lid) 10 min, open: connected again within 10 s of the tablet's screen being unlocked
- [x] Tablet screen off 30 min (Bridge exempt), unlock: connected within 10 s; clipboard Mac → tablet works
- [x] Switch the Mac to another Wi-Fi network (or a phone hotspot) and the tablet with it, then back: reconnects both times
      without Forget/re-pair; if the Mac's IP changed, the tablet follows it (mDNS)
- [x] Watchdog: `adb shell am stopservice dev.bridge.android/.BridgeService` (or `adb shell am kill dev.bridge.android` with the
      screen off), wait ≤ 15 min (or `adb shell cmd jobscheduler run -f dev.bridge.android <job id>` from
      `adb shell dumpsys jobscheduler | grep -A3 dev.bridge.android`): "Bridge is running" notification is back and connected
- [x] Manual address: Forget on both sides, Mac → Pair a device…, on the tablet type the Mac's IP in "Mac address", scan: pairs.
      Then, with the tablet offline, type a wrong address under the paired Mac → stays offline; type the right one → connects

## M2.1 gate (notifications)
Rebuild core (`./gradlew :core:assembleBridgeCoreDebugXCFramework`), the Mac app, and `./gradlew :apps:android:installDebug`.
- [x] Tablet: Notifications → turn on "Show this tablet's notifications on the Mac": the explanation and "Allow notification
      access" appear; the button opens Bridge's switch in Settings (if Android says "Restricted setting", allow it in
      App info → ⋮ → Allow restricted settings); back in Bridge the button is gone
- [x] **Gate:** a WhatsApp message to the tablet appears on the Mac within 1 s, under the WhatsApp group, with the WhatsApp
      icon as the thumbnail; the first one triggers the macOS Notifications permission prompt
- [x] **Gate:** Mac → Reply on that notification, type, Send: the message is delivered in WhatsApp (check the chat) and no
      second banner for your own reply appears on the Mac
- [x] A Mac action button (e.g. "Mark as read") works on the tablet
- [x] Close the notification on the Mac: it disappears from the tablet's shade. Swipe one away on the tablet: it
      disappears from the Mac's Notification Center
- [x] Choose apps… → turn WhatsApp off: its notifications stay on the tablet only; on again: they mirror
- [x] Turn on Do Not Disturb on the Mac: no banner, the notification is in Notification Center. Same while sharing the screen
      (e.g. a Zoom/Meet screen share or AirPlay mirroring) with the default notification settings
- [x] Music playback and the "Bridge is running" notification are never mirrored

## M2.2 gate (trackpad and keyboard)
Rebuild core (`./gradlew :core:assembleBridgeCoreDebugXCFramework`), the Mac app, and `./gradlew :apps:android:installDebug`.
If Bridge is already in System Settings → Privacy & Security → Accessibility from an older build, remove it (−) first:
the grant is tied to the code signature, and a stale entry looks on but injects nothing.
- [x] Tablet: home → Trackpad (enabled only while the Mac is connected): full screen, bars hidden, screen stays on
- [ ] Without Accessibility: the first touch opens "Let the tablet control this Mac?" on the Mac; Open System Settings goes to
      the Accessibility list with Bridge in it. The tablet shows the "allow Bridge under … Accessibility" error and the Mac
      menu shows the same cause. Turn Bridge on: the pointer moves on the next touch, no restart
- [x] One finger moves the pointer; slow is precise, a fast swipe crosses the screen; it stops at screen edges
- [ ] Tap = click; two quick taps open a Finder item (double-click); two-finger tap = context menu
- [x] Two-finger drag scrolls a Safari page and a Finder list both ways; the content follows the fingers
- [ ] Pinch out / in in Safari zooms in / out (⌘= / ⌘−)
- [x] Keyboard: type a sentence including an autocorrected word and a backspace; it matches on the Mac. Return in the field
      presses Return. ⌘ then "c" / "v" copies and pastes; ⌘ then tab-key; esc and the arrows work; ⌥ then ⌫ deletes a word
- [ ] Edge swipes near the pad's sides don't leave the trackpad screen (system back may still win in the outer strip)
- [x] **Gate:** five minutes of normal use (browse, open files, type a short note) with no noticeable lag or stuck buttons
      (passed 2026-11-07 after the TCP_NODELAY and Wi-Fi-lock fixes; residual Wi-Fi jitter accepted, see STATUS known issues)

## M2.3 gate (media, battery, share)
Rebuild core (`./gradlew :core:assembleBridgeCoreDebugXCFramework`), the Mac app, and `./gradlew :apps:android:installDebug`.
On the tablet turn on the three switches under "Media, battery and links".
Adding the Apple Events entitlement changed the Mac app's signature, and a re-signed dev build can lose its **Local Network**
grant: the Mac listens but the tablet says "Can't reach …:53318". Allow the prompt, or turn Bridge off and on again in
System Settings → Privacy & Security → Local Network. If Services → Send to Tablet is missing, tick it under Keyboard →
Keyboard Shortcuts… → Services → Text, or run `/System/Library/CoreServices/pbs -flush`.
- [ ] Spotify playing on the Mac, tablet home open: the card shows title, artist, Spotify, a moving progress bar. The
      first time, the Mac asks "Bridge wants access to control Spotify"; after OK the card fills within 2 s
- [ ] Deny that prompt (reset with `tccutil reset AppleEvents dev.bridge.mac` first): the Mac menu names the Automation setting
- [ ] Tap ⏸ / ▶: Spotify pauses / plays within ~300 ms and the button flips. ⏭ / ⏮ change the track. Volume slider changes
      Spotify's volume on release
- [ ] Seek in Spotify on the Mac: the tablet's bar jumps within ~1 s. Quit Spotify: "Nothing playing". Music.app works the same
- [ ] Leave the tablet's home screen (home button): within a second the Mac stops polling (Activity Monitor: no Apple Events to Spotify)
- [ ] Battery switch on: the Mac's menu bar shows e.g. "82%" beside the icon and in the popover; plug in the charger → "⚡︎"
      appears within a few seconds; unplug → it goes
- [ ] **Gate:** play/pause Spotify on the Mac from the tablet
- [x] **Gate:** Chrome on the tablet → Share → Bridge → the page opens in the Mac's default browser; a toast says "Sent to <Mac>"
- [x] Share plain text (e.g. from Keep) → it's on the Mac's clipboard with a "Text from your tablet" notice
- [x] Mac: select text in Safari → right-click → Services → Send to Tablet → a tablet notification; tap opens the share sheet.
      Select a URL → the notification's tap opens it in the tablet's browser
- [x] Links switch off on the tablet: Services → Send to Tablet makes the Mac menu say it's turned off there; Share → Bridge
      on the tablet toasts the switch's name
- [x] Tablet offline: Services → Send to Tablet opens the popover with "The tablet isn't connected"

## One-time device checks
- [ ] Android version reported by the Pad 3 in Settings → About
- [ ] `packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)` on the Pad 3 (needed for Tier B AirDrop)
- [ ] `Presentation` display support on the Pad 3 (needed for F10)
