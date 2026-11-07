# Bridge — Mac ↔ Android Continuity App: Product & Technical Spec

Version 1.0 · 6 Nov 2026 · Rupesh

## 1. Overview

Bridge brings Apple-Continuity-style features to a MacBook and an Android tablet on the same Wi-Fi: shared clipboard, LocalSend-style file sharing, notification mirroring, remote input, screen mirroring in both directions, second display, and AirDrop interoperability. Everything runs peer-to-peer on the local network with end-to-end encryption; no cloud account, no relay server.

**Target devices (v1)**

| Device | Role | Minimum OS |
| --- | --- | --- |
| MacBook Air M5 | Host | macOS 26 on this machine; app minimum macOS 14 Sonoma for other Macs |
| OnePlus Pad 3 (Android tablet) | Peer | Latest OxygenOS (shipped with Android 15); app minSdk stays Android 10 for other tablets |
| iPhone / iPad (future) | Peer | iOS 17 |

**Goals**

- Zero-config discovery: devices find each other automatically on the LAN.
- One-time pairing (QR code), then everything works silently in the background.
- Native feel on each platform: menu-bar app on Mac, quick-settings tile + foreground service on Android.
- Wire protocol that a future iOS app can implement without changes.
- File sharing that interoperates with the LocalSend protocol, so any LocalSend client (Windows, Linux, iOS) can already send to and receive from Bridge.

**Non-goals (v1)**

- Internet/relay mode (devices on different networks).
- Phone calls, SMS relay, or cellular features.
- App store distribution; v1 is sideloaded/notarised builds for personal use.
- Handoff (continuing an open app/document on the other device).

**Target tablet:** OnePlus Pad 3 (13.2-inch 3392×2400 LCD at up to 144 Hz, Snapdragon 8 Elite, USB-C 3.2) on the latest OxygenOS release. The 144 Hz panel and flagship decoder make 60 fps mirroring comfortable; full 3.4K streaming is possible but bandwidth-heavy, so half resolution is the default (see 5.8).

## 2. Features and phases

Four phases, each shippable on its own. Phase 1 is the foundation every later feature rides on.

| # | Feature | Direction | Phase | Difficulty | Notes |
| --- | --- | --- | --- | --- | --- |
| F1 | Device discovery + QR pairing | both | 1 | Medium | mDNS + QR code key exchange; prerequisite for everything |
| F2 | Universal clipboard (text) | both | 1 | Easy | Android → Mac only while Bridge is in the foreground (Android 10+ restriction) |
| F3 | File sharing (LocalSend-compatible) | both | 1 | Medium | Files, folders, multiple files; progress UI; works with any LocalSend client |
| F4 | Clipboard images and files | both | 2 | Easy | Rides on F2 + F3 |
| F5 | Notification mirroring | tablet → Mac | 2 | Easy | Show, dismiss, inline reply |
| F6 | Remote input: tablet as trackpad/keyboard | tablet → Mac | 2 | Medium | Needs Accessibility permission on Mac |
| F7 | Media controls + battery status | both | 2 | Easy | Now-playing, play/pause, tablet battery in Mac menu bar |
| F12 | Shared text/URL "send to device" | both | 2 | Easy | Share sheet on Android, Services menu on Mac |
| F13 | AirDrop interoperability | tablet ↔ any Apple device | 3 (own stack); 2 only if Quick Share support reaches the Pad 3 | Medium → Hard | Tablet appears in the AirDrop sheet of any iPhone, iPad or Mac and can send to them; visibility toggle Off / Bridge devices / Everyone. See 5.10 |
| F8 | Screen mirroring: tablet → Mac | tablet → Mac | 3 | Medium | Integrate scrcpy server; USB and Wi-Fi |
| F9 | Screen mirroring: Mac → tablet | Mac → tablet | 3 | Hard | H.264 stream of the Mac display; touch passthrough |
| F10 | Second display (Sidecar-style extend) | Mac → tablet | 4 | Very hard | Virtual display on macOS; committed, F9 is the fallback |
| F11 | iPhone/iPad client | both | 4 | Medium | Same protocol; file sharing already works via LocalSend on day one |

## 3. Architecture

Each device runs a thin native shell over one shared core written in Kotlin Multiplatform, so protocol logic is written once and the future iOS app only needs its own adapters.

```
┌──────────────────────────────┐                        ┌──────────────────────────────┐
│ MacBook Air M5               │                        │ OnePlus Pad 3                │
│ ┌──────────────────────────┐ │                        │ ┌──────────────────────────┐ │
│ │ macOS app (SwiftUI)      │ │                        │ │ Android app (Compose)    │ │
│ │ menu bar, settings,      │ │   mDNS discovery       │ │ foreground service, tile,│ │
│ │ launch-at-login agent    │ │ <--------------------> │ │ share-sheet target       │ │
│ ├──────────────────────────┤ │                        │ ├──────────────────────────┤ │
│ │ Platform adapters        │ │   TLS WebSocket control│ │ Platform adapters        │ │
│ │ NSPasteboard, UNUserNotif│ │ <--------------------> │ │ ClipboardManager, share  │ │
│ │ ScreenCaptureKit+VTB     │ │                        │ │ NotificationListener     │ │
│ │ CGEvent, Services        │ │   HTTPS files(LocalSend│ │ MediaCodec, Wi-Fi Aware  │ │
│ ├──────────────────────────┤ │ <--------------------> │ ├──────────────────────────┤ │
│ │ Shared core (KMP)        │ │                        │ │ Shared core (KMP)        │ │
│ │ discovery, pairing,      │ │   UDP / WebRTC video   │ │ same module, compiled    │ │
│ │ sessions, protocol,      │ │ <--------------------> │ │ for Android              │ │
│ │ clipboard, notifications,│ │            |           │ │                          │ │
│ │ LocalSend file transfer  │ │            |           │ │                          │ │
│ └──────────────────────────┘ │            |           │ └──────────────────────────┘ │
└──────────────────────────────┘            |           └──────────────────────────────┘
                                 ┌ ─ ─ ─ ─ ─ ┴ ─ ─ ─ ─ ─ ┐
                                   iPhone / iPad (future)
                                   same wire protocol, same core via KMP
                                 └ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┘
```

The macOS and Android apps differ only in their UI shell and platform adapters; the shared core is the same Kotlin module on both. A future iOS app reuses that core through KMP's native (Swift) target.

**Components**

| Component | Responsibility | Lives in |
| --- | --- | --- |
| Discovery | Advertise and find peers on the LAN via mDNS (`_bridge._tcp` and `_localsend._tcp`) | Shared core, with a platform mDNS adapter |
| Pairing | One-time trust: QR code carries the device's public key + a short session code; both sides store the peer's key | Shared core |
| Session | Persistent TLS 1.3 WebSocket per peer; heartbeat, reconnect with backoff, message routing | Shared core |
| Feature modules | Clipboard, notifications, remote input, media, file transfer, screen stream | Shared core (logic) + platform adapters (OS APIs) |
| Video pipeline | Capture → encode → packetize → decode → render | Platform-native (VideoToolbox / MediaCodec); only signalling goes through the core |

**Transport channels**

| Channel | Transport | Used for | Why separate |
| --- | --- | --- | --- |
| Discovery | mDNS/DNS-SD (Bonjour on Mac, NsdManager on Android) | Finding peers, announcing capabilities | Standard, zero-config, same mechanism LocalSend uses |
| Control | WebSocket over TLS, port 53317+1 | Clipboard, notifications, input events, media, battery, signalling | Low-latency, ordered, bidirectional, small messages |
| Files | HTTPS REST, port 53317 | File/folder transfer, LocalSend-compatible | Interop with every LocalSend client; resumable; separate from control so a 2 GB file never blocks a clipboard update |
| Video | UDP (RTP-style) or WebRTC data channel | Screen mirroring frames | Needs lowest latency; tolerates dropped frames; unordered |

**Security model**

- Each device generates an Ed25519 identity key pair on first launch and a self-signed TLS certificate derived from it; the certificate fingerprint is the device ID.
- Pairing exchanges fingerprints via QR code (shown on one device, scanned by the other) plus a 6-digit confirmation code displayed on both to defeat a QR-swap attack. Trust is stored in the OS keystore (Keychain / Android Keystore).
- All channels use TLS with certificate pinning against the paired fingerprint. Unpaired devices can discover Bridge but every request is rejected until paired, except LocalSend-compatible file receives, which prompt the user per transfer (LocalSend's own model).
- No data ever leaves the LAN. No analytics, no accounts.
- Clipboard contents are never persisted to disk on the receiving side beyond the OS clipboard itself.

## 4. Wire protocol

The control channel carries small JSON messages in one envelope; files go over a separate HTTPS API that is byte-compatible with the LocalSend protocol (v2.1). Protocol version is negotiated at connect so an iOS client built later can join without breaking older builds.

**Envelope (control channel, JSON over WebSocket text frames)**

```json
{
  "v": 1,
  "id": "01J9X7R4P3A5Q2",
  "type": "clipboard.set",
  "ts": 1762440000000,
  "replyTo": null,
  "payload": { }
}
```

| Field | Type | Meaning |
| --- | --- | --- |
| v | int | Protocol major version; mismatch → close with code 4001 |
| id | ULID string | Unique per message; used for acks and dedup |
| type | string | Dotted `module.action` name, see below |
| ts | int | Sender's epoch millis; used for clipboard last-writer-wins |
| replyTo | string or null | id of the request this answers |
| payload | object | Module-specific body |

Binary payloads (clipboard images, thumbnails) are sent as a WebSocket binary frame immediately after the JSON frame that announces them, with the JSON carrying `"binaryLength"` and `"mime"`.

**Message types**

| Type | Direction | Payload | Phase |
| --- | --- | --- | --- |
| hello | both | deviceId, name, platform, appVersion, protocolVersion, capabilities[] | 1 |
| ping / pong | both | none; every 15 s, drop after 3 misses | 1 |
| pair.request / pair.confirm / pair.reject | both | fingerprint, confirmCode | 1 |
| clipboard.set | both | mime, text or binaryLength, sourceApp | 1 |
| clipboard.request | both | none; asks the peer to resend current clipboard | 1 |
| file.offer | both | sessionId, files[] summary; points at the HTTPS file API | 1 |
| notif.posted | tablet → Mac | key, app, title, text, icon (binary), actions[], replyable | 2 |
| notif.dismissed | both | key | 2 |
| notif.action / notif.reply | Mac → tablet | key, actionIndex or replyText | 2 |
| input.pointer | tablet → Mac | dx, dy, buttons, scrollX, scrollY, gesture | 2 |
| input.key | tablet → Mac | keyCode, modifiers, text | 2 |
| media.state / media.command | both | title, artist, playing, position; play, pause, next, prev, volume | 2 |
| battery.state | both | level, charging | 2 |
| share.text | both | text or url | 2 |
| screen.offer / screen.answer / screen.ice | both | WebRTC SDP and ICE candidates (signalling only) | 3 |
| screen.touch | tablet → Mac | normalised x, y, action; mapped to the mirrored display | 3 |
| error | both | code (snake_case string), message; the failed request is the envelope's replyTo | 1 |

**File transfer API (LocalSend-compatible)**

Bridge implements the LocalSend v2.1 HTTP protocol unchanged, so any LocalSend app can already send files to Bridge and receive from it. Spec: [LocalSend protocol](https://github.com/localsend/protocol).

| Endpoint | Method | Purpose |
| --- | --- | --- |
| /api/localsend/v2/info | GET | Device info and capabilities |
| /api/localsend/v2/register | POST | Discovery fallback over HTTP when mDNS is blocked |
| /api/localsend/v2/prepare-upload | POST | Sender announces files; receiver returns per-file upload tokens (auto-accept for paired Bridge peers, prompt otherwise) |
| /api/localsend/v2/upload | POST | One file per request, `?sessionId=&fileId=&token=`, raw body |
| /api/localsend/v2/cancel | POST | Abort a session |

Bridge extensions, only between two Bridge peers: resumable uploads via a `Range` header, folder structure preserved through the `fileName` path, and progress events on the control channel. Default port 53317 so existing LocalSend clients find Bridge without configuration.

## 5. Feature specifications

### 5.1 Discovery and pairing (F1)

- Both apps advertise `_bridge._tcp` with TXT records: `id` (fingerprint), `name`, `platform`, `v` (protocol), `caps` (comma list). Bridge also advertises `_localsend._tcp` for LocalSend clients.
- Pairing flow: Mac shows a QR code (fingerprint + IP + port + 6-digit code). Tablet scans it, connects over TLS, sends `pair.request` with its own fingerprint. Both screens show the same 6-digit code; the user taps Confirm on the tablet. Mac stores the tablet's fingerprint; tablet stores the Mac's.
- Either side can initiate; the QR can equally be shown on the tablet and scanned by the Mac webcam.
- Unpairing from either device deletes the stored key and closes the session.
- Reconnect: on network change or app start, resolve the paired peer via mDNS; fall back to the last known IP for 30 s.

### 5.2 Universal clipboard (F2, F4)

- Mac polls `NSPasteboard.general.changeCount` every 250 ms (no change event exists). Android registers `OnPrimaryClipChangedListener` while in the foreground.
- On change, send `clipboard.set` with text (UTF-8, up to 1 MB) or an image (PNG, up to 10 MB). Larger content is offered as a file instead.
- Receiving side writes to the OS clipboard and marks the entry with a Bridge origin tag so it does not echo back. Conflicts resolve by `ts`, newest wins.
- Android 10+ limitation: a background app cannot read the clipboard. Mitigations, in order of preference: (a) quick-settings tile "Send clipboard"; (b) share-sheet target "Send to Mac"; (c) a persistent notification with a "Sync clipboard" action. Mac → tablet always works because writing the clipboard is allowed in the background.
- Sensitive content: skip items flagged `org.nspasteboard.ConcealedType` (password managers) on Mac and `EXTRA_IS_SENSITIVE` on Android.

### 5.3 File sharing, LocalSend-compatible (F3)

- Send from Mac: drag files onto the menu-bar icon, a Finder Services menu item "Send to <tablet>", or a Share-menu extension.
- Send from tablet: share-sheet target, or in-app file picker (SAF) supporting multiple files and folders.
- Receive: paired peers auto-accept into a configurable folder (`~/Downloads/Bridge` on Mac, `Download/Bridge` on Android). Unpaired LocalSend clients trigger an accept/decline prompt listing file names and sizes.
- Progress UI on both sides: per-file and total bytes, speed, cancel. Transfers survive a brief Wi-Fi drop via `Range` resume (Bridge peers only).
- Interop test matrix: LocalSend on Windows, Linux, iOS and Android must be able to send to and receive from both Bridge apps.

### 5.4 Notification mirroring (F5)

- Tablet `NotificationListenerService` forwards new notifications (`notif.posted`) with app name, icon (32 px PNG), title, text, actions, and whether a `RemoteInput` exists.
- Mac shows them via `UNUserNotificationCenter` grouped by app. Clicking an action sends `notif.action`; typing in the reply field sends `notif.reply`, which the tablet injects into the `RemoteInput`.
- Dismissing on either side sends `notif.dismissed` so both stay in sync.
- Per-app allow/block list on the tablet; a global mute when the Mac is in Do Not Disturb or presenting.

### 5.5 Remote input (F6)

- Tablet trackpad screen: one finger moves the pointer, tap = click, two-finger drag = scroll, two-finger tap = right-click, pinch = zoom. A keyboard view sends `input.key` including modifiers.
- Mac injects events with `CGEvent`; requires the Accessibility permission, requested on first use with a guided prompt.
- Rate limited to 120 events/s with coalescing of pointer deltas.

### 5.6 Media controls and battery (F7)

- Mac reads now-playing via `MediaRemote` (private framework; fallback to AppleScript for Music/Spotify) and sends `media.state`; tablet shows a controller and sends `media.command`.
- Tablet sends `battery.state` every 5 minutes or on charger change; Mac menu bar shows the tablet's level.

### 5.7 Screen mirroring: tablet → Mac (F8)

- Bundle the scrcpy server (`scrcpy-server.jar`) and push it over ADB (Wi-Fi ADB or USB). The Mac app embeds a scrcpy-compatible client view: H.264 via VideoToolbox decode, keyboard/mouse forwarded as touch.
- Requires Developer options + wireless debugging on the tablet once; the app guides the user through it.
- Target: 1080p at 60 fps, under 100 ms latency over Wi-Fi 5.

### 5.8 Screen mirroring: Mac → tablet (F9)

- Capture with `ScreenCaptureKit` (one display or one window), encode H.264 with `VideoToolbox` hardware encoder at 1696×1200 (half of the Pad 3's 3392×2400) by default at 60 fps, 8–20 Mbit/s adaptive; full 3.4K as an opt-in quality setting.
- Transport over WebRTC (signalled on the control channel) so congestion control and NACK/FEC come for free; fallback raw RTP over UDP.
- Tablet decodes with `MediaCodec` into a `SurfaceView`; touches are sent as `screen.touch` and injected on the Mac as pointer events mapped to the captured display.
- Target: under 60 ms glass-to-glass on Wi-Fi 6.

### 5.9 Second display (F10)

- Requires a virtual display on macOS. Options: (a) the private `CGVirtualDisplay` API used by several open-source projects, (b) a DriverKit virtual display driver, which needs an Apple entitlement. Start with (a) for personal use.
- Once the virtual display exists, F9's pipeline captures it instead of the main display and the tablet becomes an extended desktop.
- Open question: whether OxygenOS on the Pad 3 supports `Presentation` displays for a second surface; otherwise the app occupies the whole screen, which is fine for v1.

### 5.10 AirDrop interoperability (F13)

Goal: with AirDrop sharing turned on, the Pad 3 shows up in the AirDrop sheet of any nearby iPhone, iPad or Mac, and the tablet can send to any of them, with the same three-way visibility control AirDrop has. The Mac needs nothing new: it uses its native AirDrop.

**How Apple ↔ Android AirDrop works today**

- [Google shipped Quick Share–AirDrop interoperability](https://www.thurrott.com/mobile/335942/quick-share-compatibility-with-airdrop-is-coming-to-oneplus-xiaomi-oppo-honor-and-vivo-phones) on Pixel 10 in November 2025 and is extending it in 2026 to OnePlus, Oppo, Vivo, Xiaomi, Honor and Samsung. [It reached the OnePlus 15 on 9 June 2026](https://www.androidauthority.com/oneplus-15-airdop-via-quick-share-3675598) as a Play Store update to the Quick Share app, not a firmware update. Whether the Pad 3 is covered is unconfirmed; it shares the OnePlus 15's Snapdragon 8 Elite, and chipset is believed to decide eligibility.
- It works because AirDrop now runs over Wi-Fi Aware (the same standard Android supports) with BLE for discovery; Google built the client without Apple's help.
- [Setup on both sides](https://www.gizmochina.com/?p=741555): the Android device sets Quick Share visibility to Everyone and the Apple device sets AirDrop to "Everyone for 10 minutes". Contacts Only cannot work across platforms: it relies on Apple ID identity hashes that only Apple devices can present, so an Android device is invisible to an iPhone in Contacts Only mode no matter what we build.

**Bridge's approach: two tiers**

| Tier | What | When | Effort |
| --- | --- | --- | --- |
| A — Quick Share bridge | If the Pad 3 receives Quick Share–AirDrop support, Bridge does not reimplement anything: the AirDrop toggle in Bridge drives Quick Share's visibility (Everyone / Off), Bridge registers as a Quick Share receive target so incoming AirDrops land in the Bridge folder and show in the transfer list, and the share sheet "AirDrop" action opens Quick Share pre-targeted at Apple devices | Phase 2 | Low |
| B — Native AirDrop stack | Bridge implements the AirDrop protocol itself: BLE advertisement in Apple's AirDrop format, a Wi-Fi Aware (NAN) session via `WifiAwareManager`, then AirDrop's HTTPS service (`/Discover`, `/Ask`, `/Upload`) with a self-signed cert. Everyone mode only, as above. Validated first with a desktop spike against the open-source OpenDrop/OWL implementation, then ported to Android | Phase 3 spike, ship if the spike passes | High; depends on OxygenOS exposing Wi-Fi Aware to apps |

Tier A is not available today: checked on 6 Nov 2026, the MacBook does not appear in the Pad 3's Quick Share even with the latest Quick Share. Tier B is therefore the plan of record, with Tier A swapped in the moment Google enables the Pad 3. Tier B is a research task, not a guaranteed feature: Apple can change the protocol, and Wi-Fi Aware support varies per device firmware.

**Visibility toggle (matches iPhone's three options as closely as the protocol allows)**

| Bridge option | AirDrop equivalent | Behaviour |
| --- | --- | --- |
| Receiving Off | Receiving Off | No BLE advertisement; tablet invisible to Apple devices; sending still allowed |
| Bridge devices only | Contacts Only | Tablet is invisible to AirDrop (Apple's Contacts Only cannot see non-Apple devices); file receive stays open only to paired Bridge/LocalSend peers. Labelled honestly in the UI: "Apple devices can't see you in this mode" |
| Everyone for 10 minutes | Everyone for 10 minutes | Advertises to all nearby Apple devices; auto-reverts to Bridge-only after 10 minutes, with an "always" override for home use |

The toggle lives in Bridge settings, the quick-settings tile and the Mac menu bar (so you can flip the tablet to Everyone from the Mac before sending). Incoming AirDrops always show an accept/decline card with sender name, file names and sizes, mirroring iOS.

**Sending from the tablet**: share sheet → Bridge → nearby Apple devices listed by their AirDrop name alongside paired Bridge peers; the Apple device must be in Everyone mode. Sending to the paired Mac defaults to the LocalSend channel (faster, no 10-minute window) with AirDrop as an explicit alternative.

**Out of scope**: Contacts Only visibility to Apple devices, AirDrop of Passes/contacts/URLs with native handlers (plain files only), and NameDrop.

## 6. Platform specifics

**Shared core (`core/`, Kotlin Multiplatform)** — targets `jvm` for tests, `android`, `macosArm64`/`macosX64` and later `iosArm64`. Contains: protocol models and codec (kotlinx.serialization), session state machine, pairing, clipboard/notification/input/media modules, LocalSend HTTP client and server (Ktor), discovery interface. Platform code implements small `expect`/`actual` interfaces: `ClipboardPort`, `NotificationPort`, `InputPort`, `MediaPort`, `DiscoveryPort`, `KeyStorePort`. Exposed to Swift as an XCFramework.

**macOS app (`apps/macos/`, Swift 5.10, SwiftUI + AppKit)**

- `LSUIElement` menu-bar app with a settings window; launch-at-login via `SMAppService`.
- Permissions needed and when they are requested: Local Network (first run), Screen Recording (first mirror), Accessibility (first remote-input use), Notifications (first notification mirror).
- Not sandboxed in v1 (ADB, virtual display and `CGEvent` need it); hardened runtime + notarised for Gatekeeper.
- Bonjour via `NWBrowser`/`NWListener`; TLS via `NWProtocolTLS` with the identity from Keychain.
- Video: `ScreenCaptureKit` → `VTCompressionSession` → WebRTC (`WebRTC.xcframework` from the Google build).

**Android app (`apps/android/`, Kotlin, Jetpack Compose, minSdk 29, targetSdk 35)**

- Foreground service (`FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`) keeps the session alive; persistent low-priority notification with Send clipboard / Send file actions.
- Quick-settings tile for clipboard sync; share-sheet target for files, text and URLs.
- `NsdManager` for mDNS, `NotificationListenerService` for F5, `MediaCodec` + `SurfaceView` for F9, scrcpy server for F8.
- Battery optimisation exemption requested on setup so the service is not killed.

**iOS app (`apps/ios/`, future)** — Same XCFramework core. Known constraints to design around now: no background clipboard reading, no notification listener, no screen capture of other apps (ReplayKit covers only broadcast of the whole screen with user consent), no `CGEvent`. Realistic iOS feature set: file sharing (already works today through LocalSend), clipboard via share sheet and shortcuts, media controls, battery, and receiving a Mac screen stream. The protocol's `capabilities[]` in `hello` lets each peer hide features the other cannot do.

## 7. UX, pairing flow and settings

**First-run pairing**

1. Install Bridge on both devices and connect them to the same Wi-Fi.
2. Mac: click the menu-bar icon → Pair a device. A QR code appears with a 6-digit code under it.
3. Tablet: open Bridge → Scan QR. The tablet connects and shows the same 6-digit code.
4. Confirm on the tablet. Both apps show "Paired with <name>". Clipboard sync is on by default; everything else is opt-in per feature.
5. Each feature's first use triggers its OS permission prompt with a one-line explanation of why.

**Mac menu-bar menu**: paired devices with connection status and tablet battery · Send clipboard now · Send files… · Mirror tablet · Mirror to tablet · Use tablet as trackpad · Settings · Quit.

**Tablet home screen**: connection card (Mac name, status, link quality) · big buttons: Send clipboard, Send files, Trackpad, View Mac screen · recent transfers list · per-feature toggles.

**Settings (both platforms)**

| Setting | Default | Notes |
| --- | --- | --- |
| Device name | OS hostname | Shown to peers, LocalSend clients and Apple AirDrop sheets |
| Clipboard sync | On | Text + images; max size slider |
| Receive folder | Downloads/Bridge | Per-device |
| Auto-accept files | Paired devices only | Options: always ask / paired only / everyone on LAN |
| AirDrop visibility | Bridge devices only | Receiving Off / Bridge devices only / Everyone for 10 minutes; also in the quick tile and Mac menu bar (see 5.10) |
| Notification mirroring | Off | Per-app list on tablet |
| Mirroring quality | Auto | Resolution, fps and bitrate caps |
| Trusted networks | All | Optionally restrict to named Wi-Fi SSIDs |
| Launch at login / boot | On | |

**Mobile and desktop alike:** every action that touches the other device gives feedback within 300 ms (toast on Android, HUD on Mac), and every failure names the cause (peer offline, permission missing, file rejected) rather than silently doing nothing.

## 8. Tech stack, repo layout and testing

**Stack**

| Layer | Choice | Reason |
| --- | --- | --- |
| Shared core | Kotlin Multiplatform 2.x, kotlinx.coroutines, kotlinx.serialization, Ktor client + server (CIO engine) | One protocol implementation; Kotlin is closest to the Java you already write; Ktor gives the LocalSend HTTP server for free |
| Crypto | Ed25519 + X25519 via `libsodium` bindings (KMP), platform TLS stacks | Small, audited, available on all three OSes |
| macOS UI | SwiftUI + AppKit, Swift Package Manager | Native menu-bar app; XCFramework from KMP |
| Android UI | Jetpack Compose, Hilt, WorkManager | Standard modern Android |
| Video | WebRTC (Google prebuilt), VideoToolbox, MediaCodec, scrcpy server | Hardware codecs on both sides; scrcpy is the proven tablet → Mac path |
| Build | Gradle (core + Android), Xcode + SPM (Mac), GitHub Actions on macOS runners | KMP's Apple targets need a Mac to build |

**Repository layout (monorepo)**

```
bridge/
  core/                 # KMP: protocol, session, modules, LocalSend server/client
    src/commonMain/
    src/androidMain/
    src/appleMain/
    src/commonTest/
  apps/
    macos/              # Xcode project, links core.xcframework
    android/            # Gradle app module
    ios/                # future
  tools/
    protocol/           # JSON schema for every message type, generated docs
    scrcpy/             # pinned scrcpy-server.jar + licence
  docs/
    spec.md             # this document
    STATUS.md           # current phase and milestone
    testing.md          # device checklist
    adr/                # architecture decision records
```

**Testing**

- Unit tests in `core/commonTest`: codec round-trips for every message type, session state machine, pairing, clipboard conflict resolution, LocalSend request parsing.
- Contract tests: a JVM test harness that runs two `core` instances on localhost and drives every feature end-to-end without any UI.
- LocalSend interop: CI job downloads the official LocalSend CLI/desktop build and exchanges files with the `core` server in both directions.
- Device tests: a manual checklist per release on the real MacBook + tablet covering pairing, reconnect after sleep, Wi-Fi switch, large file (2 GB), 30-minute mirroring session. See `docs/testing.md`.
- Performance budgets asserted in tests: clipboard propagation < 150 ms, file throughput ≥ 40 MB/s on Wi-Fi 5, mirroring latency measured with a timestamp overlay.

## 9. Roadmap and milestones

Proposed pacing assumes evenings and weekends with Claude Code doing most of the typing; each phase is roughly six to eight weeks and ends at a gate you can verify on your own devices.

| Phase | When | Contents | Gate |
| --- | --- | --- | --- |
| 1 · Foundation | Nov–Dec 2026 | Discovery + QR pairing, clipboard (text), LocalSend file sharing, Mac + Android shells | Files interop with LocalSend |
| 2 · Daily driver | Jan–Feb 2027 | Notification mirroring, trackpad + keyboard, media/battery/share, clipboard images, AirDrop toggle plumbing | Two weeks of daily use |
| 3 · Mirroring | Mar–Apr 2027 | Tablet → Mac (scrcpy), Mac → tablet (WebRTC), touch passthrough, native AirDrop spike | Under 100 ms mirroring latency |
| 4 · Extend | May 2027 onward | Second display, iPhone/iPad client, relay mode (maybe) | iPhone pairs and shares files |

Phase 1 is the only one that must come first; phases 2 and 3 can swap if screen mirroring matters more than notifications.

**Phase 1 milestones**

- [ ] M1.1 `core` builds for JVM, Android and macOS; `hello`/`ping` round-trip in a JVM test
- [ ] M1.2 mDNS discovery + QR pairing between the real MacBook and tablet
- [ ] M1.3 Clipboard text Mac → tablet and tablet → Mac (via tile)
- [ ] M1.4 LocalSend server passes interop with the official LocalSend app, both directions
- [ ] M1.5 Mac menu-bar app and Android foreground service stay connected across sleep/wake

**Phase 2 milestones**

- [ ] M2.1 Notifications mirrored with dismiss sync and inline reply
- [ ] M2.2 Trackpad and keyboard from the tablet with Accessibility onboarding
- [ ] M2.3 Media controls, battery, share-to-device
- [ ] M2.4 AirDrop visibility toggle, receive-folder and transfer-list plumbing (UI only; wired to Tier A if Quick Share support reaches the Pad 3, otherwise to Tier B in Phase 3)
- [ ] M2.5 Two weeks of daily use without manual reconnects

**Phase 3 milestones**

- [ ] M3.1 Tablet → Mac mirroring via bundled scrcpy, USB and Wi-Fi
- [ ] M3.2 Mac → tablet mirroring via WebRTC at tablet-native resolution
- [ ] M3.3 Touch passthrough and latency overlay showing < 100 ms
- [ ] M3.4 Native AirDrop (Tier B): spike first, Pad 3 visible in the Mac's and an iPhone's AirDrop sheet in Everyone mode with one file received each way; go/no-go, then ship if it passes

**Phase 4**

- [ ] M4.1 Virtual display on macOS, tablet as extended desktop
- [ ] M4.2 iOS client: pairing, files, clipboard via share sheet, media, receive Mac screen

## 10. Risks, limitations and open questions

| Risk / limitation | Impact | Mitigation |
| --- | --- | --- |
| Android background clipboard read is blocked (API 29+) | Tablet → Mac clipboard is not automatic | Quick-settings tile, share sheet, persistent-notification action; document clearly in onboarding |
| OxygenOS battery manager on the Pad 3 may kill the foreground service | Session drops silently | Request battery-optimisation exemption; `WorkManager` watchdog restarts the service; reconnect on `ACTION_USER_PRESENT` |
| Google may never enable Quick Share–AirDrop on the Pad 3 (only the OnePlus 15 so far) | Tier A AirDrop unavailable | Tier B native stack spike in Phase 3; LocalSend remains the Mac path regardless |
| AirDrop Contacts Only mode can never see a non-Apple device | "Contacts" option cannot match iPhone behaviour | Bridge-devices-only mode with an honest label; Everyone-for-10-minutes is the cross-platform mode |
| Native AirDrop stack (Tier B) depends on undocumented protocol details and Wi-Fi Aware firmware support | Could break on any iOS/macOS release | Feature-flagged, spike-gated; never the only way to move files |
| macOS private APIs (`MediaRemote`, `CGVirtualDisplay`) may break on OS updates | Media controls and second display stop working | Keep them behind feature flags with fallbacks (AppleScript for media; mirroring instead of extend) |
| Mac → tablet mirroring latency over congested Wi-Fi | Unusable as a working display | Adaptive bitrate, 5 GHz guidance, USB tethering option via `adb reverse` in Phase 4 |
| Guest / client-isolated Wi-Fi blocks mDNS and peer traffic | Devices never find each other | Manual IP entry on the pairing screen; LocalSend's HTTP `register` fallback |
| scrcpy requires wireless debugging to stay enabled | Tablet mirroring needs re-enabling after reboot | Guided setup screen; USB as the always-works path |
| LocalSend protocol changes (v3) | Interop drifts | Pin to v2.1, run interop tests in CI against the latest LocalSend release |
| KMP Apple targets add build complexity for a solo developer | Slower iteration on the Mac app | Keep the core small and UI-free; test it on the JVM; only link the XCFramework from Swift |
| iOS platform restrictions | Most features cannot exist on iPhone | Capabilities negotiation hides them; iOS value is files, clipboard via share sheet, media, viewing the Mac screen |

**Decisions made**

- Tablet: OnePlus Pad 3 on the latest OxygenOS. Still to confirm on the device: the Android version it reports, `Presentation` display support, OxygenOS battery behaviour.
- Mac: MacBook Air M5 on macOS 26. Apple Silicon makes VideoToolbox encoding cheap; macOS 26 has AirDrop over Wi-Fi Aware.
- AirDrop: checked 6 Nov 2026, the Pad 3 does not show the MacBook in Quick Share, so Tier A is unavailable today and AirDrop starts as the Phase 3 Tier B spike. Re-check after each Quick Share update, with the Mac's AirDrop set to Everyone for 10 minutes and Quick Share visibility on Everyone.
- Second display (F10) is worth the private-API risk; it stays in Phase 4 as a committed feature, with F9 mirroring as the fallback if `CGVirtualDisplay` breaks.

**Open questions**

- [ ] Does OxygenOS on the Pad 3 expose Wi-Fi Aware (`PackageManager.FEATURE_WIFI_AWARE`)? Needed only for Tier B.
- [ ] Should Phase 1 ship clipboard + files only, with notifications pulled forward if they turn out quick?
- [ ] Name: "Bridge" is a placeholder; check for conflicts before publishing a repo.
