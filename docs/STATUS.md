# Status

**Current phase:** 1 — Foundation
**Current milestone:** M1.2 — mDNS discovery + QR pairing between the real MacBook and tablet

## Done
- M1.1 — `core` builds for JVM, Android and macosArm64; envelope + `hello`/`ping`/`pong`/`error` codec with
  JSON round-trip and schema-validation tests (`./gradlew :core:allTests`). See ADR 0004.

## Log
- 2026-11-06 — Spec v1.0 and repo skeleton created.
- 2026-11-07 — M1.1 done: Gradle 9.8.1 + version catalog, KMP `core` module, protocol codec, `pong.json`/`error.json` schemas.
