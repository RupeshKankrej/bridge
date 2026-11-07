# Bridge

Apple-Continuity-style features between a MacBook and an Android tablet: universal clipboard, LocalSend-compatible file sharing, notification mirroring, remote input, screen mirroring both ways, second display, and AirDrop interoperability. Peer-to-peer on the LAN, end-to-end encrypted, no cloud.

- Spec: [`docs/spec.md`](docs/spec.md)
- Current milestone: [`docs/STATUS.md`](docs/STATUS.md)
- Working with Claude Code: [`CLAUDE.md`](CLAUDE.md)

## Getting started (Phase 1, M1.1)

Prerequisites on the Mac: Xcode 16+, Android Studio with SDK 35, JDK 21 (see `.sdkmanrc`).

Open Claude Code in this directory and start with:

> Read docs/spec.md and docs/STATUS.md. We're on milestone M1.1 only: set up the Gradle build with a version catalog, create the KMP `core` module targeting jvm, android, macosArm64, implement the message envelope and the `hello` and `ping`/`pong` types from spec §4 with kotlinx.serialization, and write a JVM test that round-trips them. Don't build any UI or other features. Ask before adding any dependency not listed in spec §8.
