# Bridge — Claude Code project guide

Bridge is a Mac ↔ Android tablet continuity app: universal clipboard, LocalSend-compatible
file sharing, notification mirroring, remote input, screen mirroring both ways, second display,
and AirDrop interoperability. Peer-to-peer on the LAN, end-to-end encrypted, no cloud.

**The spec is the source of truth: `docs/spec.md`.** Read it before starting any task. If a task
contradicts the spec, stop and ask; if we agree to change direction, update `docs/spec.md` in the
same PR and add an ADR under `docs/adr/`.

## Who you're working with

Rupesh is a backend engineer (Java, Spring Boot, Kafka). Kotlin and KMP are close to home;
Swift/SwiftUI, Android UI and video pipelines are new. When touching Swift, AppKit, Compose,
ScreenCaptureKit, VideoToolbox, MediaCodec or WebRTC, explain the non-obvious parts in a short
comment or in the PR summary. Don't over-explain Kotlin, Gradle, coroutines or networking.

## Target devices

- Dev Mac: MacBook Air M5, macOS 26. App minimum: macOS 14.
- Tablet: OnePlus Pad 3 (13.2″ 3392×2400 @ 144 Hz, Snapdragon 8 Elite), latest OxygenOS.
  App minSdk 29, targetSdk 35.
- Future: iPhone/iPad (iOS 17+), same `core` via XCFramework.

## Repository layout

```
bridge/
  core/                 # Kotlin Multiplatform: protocol, session, pairing, feature modules,
                        # LocalSend HTTP client+server. NO UI, NO platform APIs except via expect/actual ports.
    src/commonMain/
    src/commonTest/     # most tests live here and run on the JVM
    src/androidMain/
    src/appleMain/
  apps/
    macos/              # Xcode project (SwiftUI + AppKit), links core.xcframework
    android/            # Gradle app module (Jetpack Compose)
    ios/                # future
  tools/
    protocol/           # JSON schema per message type; keep in sync with core models
    scrcpy/             # pinned scrcpy-server.jar + licence
  docs/
    spec.md             # the spec
    adr/                # architecture decision records, NNNN-title.md
```

## Phases and scope discipline

Work is phased (spec §2, §9). **Only build what the current milestone asks for.** Do not scaffold
future features "while you're there". Current phase and milestone are tracked at the top of
`docs/STATUS.md`; update it when a milestone completes.

Phase 1 (now): discovery + QR pairing, clipboard text, LocalSend-compatible files, Mac and
Android shells, stays connected across sleep/wake.

## Hard rules

1. **Ask before adding a dependency** not listed in spec §8. Prefer the standard library.
2. **Wire protocol changes go through the spec first.** Every message type in `core` must have a
   matching entry in spec §4 and a JSON schema in `tools/protocol/`. Bump the protocol version
   only for breaking changes.
3. **LocalSend compatibility is non-negotiable.** The HTTP API under `/api/localsend/v2/` must stay
   byte-compatible with LocalSend v2.1. Bridge-only extensions are opt-in and never change the
   base endpoints' behaviour for non-Bridge clients.
4. **No data leaves the LAN. No analytics, no telemetry, no accounts.**
5. **Security defaults:** TLS 1.3 everywhere, certificate pinning against the paired fingerprint,
   keys in Keychain / Android Keystore, nothing secret in logs. Never log clipboard contents.
6. **`core` has no platform imports.** Platform behaviour goes behind the `*Port` interfaces
   (`ClipboardPort`, `NotificationPort`, `InputPort`, `MediaPort`, `DiscoveryPort`, `KeyStorePort`).
7. **Don't touch private Apple APIs** (`MediaRemote`, `CGVirtualDisplay`) outside a feature flag,
   and never in Phase 1 or 2.
8. **Never commit** keys, certificates, signing identities, `local.properties`, or the
   `apps/*/build` directories.

## Code conventions

- Kotlin: official style, `ktlint` via Gradle. Coroutines + `Flow` for async; no RxJava.
  Serialization with `kotlinx.serialization`; models are `@Serializable data class`es in
  `core/src/commonMain/kotlin/bridge/protocol/`.
- Message types are named `module.action` in the wire and `ModuleAction` in Kotlin
  (`clipboard.set` ↔ `ClipboardSet`). IDs are ULIDs.
- Swift: Swift 5.10, SwiftUI for views, AppKit only where SwiftUI can't (menu bar, `NSPasteboard`,
  `CGEvent`). Swift Package Manager; no CocoaPods.
- Android: Compose, Hilt, single-activity. Foreground service type `connectedDevice`.
- Errors: `Result`/sealed types in `core`; never swallow exceptions silently. Log at `debug` with
  the message `id`, never the payload.
- Small, focused commits. Conventional Commits prefixes: `feat(core):`, `fix(android):`,
  `docs:`, `test:`, `chore:`.

## Build and test

```bash
# core + android
./gradlew :core:allTests                # JVM + Android unit tests (run before every commit)
./gradlew :core:assembleXCFramework     # produces core.xcframework for the Mac app
./gradlew :apps:android:installDebug    # tablet connected via USB or wireless ADB

# macOS
cd apps/macos && xcodebuild -scheme Bridge -configuration Debug build

# interop
./gradlew :core:localSendInteropTest    # needs LocalSend CLI on PATH; see docs/testing.md
```

Tests to write with every feature: codec round-trip for new message types, state-machine tests
for session/pairing changes, and a contract test in `core/src/commonTest/contract/` that drives
two `core` instances over localhost. UI is tested manually against the device checklist in
`docs/testing.md`.

## Definition of done for a milestone

- Spec section implemented as written, or spec updated with the agreed deviation.
- `./gradlew :core:allTests` green; new tests added.
- Mac app builds; Android app installs and runs on the Pad 3.
- `docs/STATUS.md` updated; any decision recorded in `docs/adr/`.
- PR summary lists what was built, what was deliberately left out, and anything that needs
  manual verification on the devices.

## When unsure

Ask. A short question beats a wrong guess, especially on anything touching permissions
(Screen Recording, Accessibility, Notification access, battery exemption), security, or the
wire protocol. If something in the spec looks wrong or infeasible, say so with the reason rather
than silently working around it.
