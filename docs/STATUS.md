# Status

**Current phase:** 2 — Daily driver (Phase 1 released as v0.1.0 on 2026-11-07)
**Current milestone:** M2.1 — Notifications mirrored with dismiss sync and inline reply

## Done
- M1.1 — `core` builds for JVM, Android and macosArm64; envelope + `hello`/`ping`/`pong`/`error` codec with
  JSON round-trip and schema-validation tests (`./gradlew :core:allTests`). See ADR 0004.
- M1.2 — Discovery + QR pairing. `core`: Discovery/KeyStore/Transport ports, P-256 identity with a self-signed
  certificate, session state machine (hello, 15 s heartbeat, reconnect backoff), `pair.*` flow, contract tests.
  Mac menu-bar app (NWListener mutual TLS 1.3 WebSocket, Bonjour) and Android app (foreground service, NsdManager,
  OkHttp, QR scanner). Device gate passed on the MacBook Air M5 + Pad 3: paired, 10 minutes idle, stale code
  refused, Forget propagates. See ADR 0005.
- M1.3 — Clipboard text both ways. `core`: `ClipboardPort`, `clipboard.set`/`clipboard.request`, last-writer-wins by
  copy time with catch-up on reconnect, origin tag, 1 MB cap, `unsupported_type` for unknown message types. Mac
  `NSPasteboard` poller (250 ms); Android listener while in front, "Send clipboard" tile and notification action.
  Device gate passed except the password-manager skip (below). See ADR 0006.
- M1.4 — LocalSend files, built as M1.4a `core`, M1.4b Mac, M1.4c Android (ADR 0007). LocalSend v2.1 server, client and
  multicast discovery in `core` over `StreamPort`/`MulticastPort`/`FileStorePort`; plain LocalSend on 53317, paired peers on
  53319 (client certificate required) with auto-accept and `Upload-Offset` resume. Mac: drag onto the menu-bar icon, Finder
  Services "Send with Bridge", Accept / Decline card, transfer list. Android: share target, SAF files and folders, offer
  notification and dialog, transfer list, wake/Wi-Fi locks during transfers. Device gate passed except throughput (below).
- M1.5 — Stays connected (ADR 0008). `core` `reconnect()` with a 5 s ping probe, mDNS-located address with the last known IP
  kept as fallback, typed Mac address. Mac open at login (`SMAppService`), reconnect on wake and network change. Android
  battery-exemption prompt, WorkManager watchdog, reconnect on unlock and network change. Phase 1 device gate passed on
  2026-11-07 (throughput and password-manager skip accepted as known issues). Tagged **v0.1.0**.

## Known issues
- M1.4 throughput gate (≥ 40 MB/s) not measured: the home Wi-Fi gives raw TCP of only ~7–8 MB/s between the Mac and the
  Pad 3, and Bridge runs at that limit. Re-run with the Mac on Ethernet and a faster access point (docs/testing.md, M1.4c
  checks). Deferred by agreement on 2026-11-07.
- Passwords copied from a password manager on the Mac still reach the tablet: the manager in use doesn't set
  `org.nspasteboard.ConcealedType`, or sets it on an item the poller doesn't check. Next step: list its pasteboard
  types and add that marker to `MacClipboard`. Deferred by agreement on 2026-11-07.

## Log
- 2026-11-07 — M2.1 built, device gate pending (docs/testing.md, M2.1 gate). `core`: `notif.posted/dismissed/action/reply`,
  `NotificationPort`, binary frames on the control channel (ADR 0009). Android `NotificationListenerService` with an
  on/off switch and per-app list; Mac `UNUserNotificationCenter` with action buttons, inline reply and dismiss sync.
- 2026-11-07 — M1.5 device gate passed, including sleep/wake, 30 min screen off, Wi-Fi switch, watchdog restart and typed
  address. Phase 1 done; tagged v0.1.0. Phase 2 starts with M2.1.
- 2026-11-07 — M1.5 built, device gate pending (docs/testing.md, M1.5 gate). `core`: `reconnect()` (dial now + 5 s ping probe),
  mDNS-located address with the last known IP kept as fallback, typed address on pairing and for an offline Mac (ADR 0008).
  Mac: `SMAppService` login item (on by default, menu toggle), reconnect on wake and `NWPathMonitor` change, listener restart
  on failure. Android: NSD browse + resolve, reconnect on unlock and network change, battery-exemption prompt, 15-minute
  WorkManager watchdog. v0.1.0 is tagged and Phase 2 starts once the gate passes.
- 2026-11-07 — M1.4c built: Android share target, SAF files/folders, offer notification + dialog, transfer list; Bridge ↔ Bridge
  transfers work both ways (1 GB and 2 GB intact). Throughput gate not met **because of the network**: raw TCP between the devices
  is ~7–8 MB/s on the current Wi-Fi (Pad 3 link 432/540 Mbps through one AP); Bridge runs at that limit. Needs a better link to judge.
- 2026-11-07 — M1.4b built: Mac `StreamPort`/`MulticastPort`/`FileStorePort` on Network.framework, menu bar moved to an
  `NSStatusItem` for drag-and-drop, Finder Services "Send with Bridge", offer prompt and transfer list. Paired peers moved
  to their own port 53319 because macOS can't do optional client certificates (ADR 0007 amended). curl → Mac app: 1 GB in
  3.1 s on localhost (~340 MB/s).
- 2026-11-07 — M1.4b device check with official LocalSend 2.2 on the Pad 3, both directions. Fixed: LocalSend announces its
  fingerprint in uppercase hex (pinning refused every send). Added: floating Accept / Decline card for incoming offers
  instead of a system notification (macOS can't show coloured side-by-side action buttons), failure reasons in the
  transfer list.
- 2026-11-07 — M1.4a built: LocalSend v2.1 server, client and multicast discovery in `core` over new `StreamPort`/
  `MulticastPort`/`FileStorePort`; client-certificate trust for paired peers; `Upload-Offset` resume; contract tests and
  `localSendInteropTest`. Ktor dropped, `_localsend._tcp` replaced by multicast (ADR 0007). Interop not yet run.
- 2026-11-06 — Spec v1.0 and repo skeleton created.
- 2026-11-07 — M1.1 done: Gradle 9.8.1 + version catalog, KMP `core` module, protocol codec, `pong.json`/`error.json` schemas.
- 2026-11-07 — M1.3 done: clipboard text both ways (ADR 0006); device gate passed except the password-manager skip,
  left as a known issue. 250 ms Mac poll kept.
- 2026-11-07 — M1.2 done: identity switched from Ed25519 to P-256 and `TransportPort` added (ADR 0005); device gate passed.
