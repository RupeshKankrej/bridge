# Status

**Current phase:** 1 — Foundation
**Current milestone:** M1.4 — LocalSend server passes interop with the official LocalSend app, both directions.
Split (ADR 0007): M1.4a `core` — built; M1.4b Mac adapters and UI — built; M1.4c Android adapters and UI — next. The gate runs after M1.4c.

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
