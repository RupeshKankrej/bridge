# Status

**Current phase:** 1 — Foundation
**Current milestone:** M1.4 — LocalSend server passes interop with the official LocalSend app, both directions

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

## Known issues
- Passwords copied from a password manager on the Mac still reach the tablet: the manager in use doesn't set
  `org.nspasteboard.ConcealedType`, or sets it on an item the poller doesn't check. Next step: list its pasteboard
  types and add that marker to `MacClipboard`. Deferred by agreement on 2026-11-07.

## Log
- 2026-11-06 — Spec v1.0 and repo skeleton created.
- 2026-11-07 — M1.1 done: Gradle 9.8.1 + version catalog, KMP `core` module, protocol codec, `pong.json`/`error.json` schemas.
- 2026-11-07 — M1.3 done: clipboard text both ways (ADR 0006); device gate passed except the password-manager skip,
  left as a known issue. 250 ms Mac poll kept.
- 2026-11-07 — M1.2 done: identity switched from Ed25519 to P-256 and `TransportPort` added (ADR 0005); device gate passed.
