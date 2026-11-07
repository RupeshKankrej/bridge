# ADR 0001 — Shared core in Kotlin Multiplatform

**Status:** accepted · 2026-11-06

## Context
Bridge needs the same protocol, session and pairing logic on macOS, Android and later iOS. The developer is strongest in Java/Kotlin.

## Decision
Implement all platform-independent logic once in a KMP module (`core/`), compiled to Android, macOS (and later iOS) targets, with platform behaviour behind `expect`/`actual` port interfaces. UI shells are native (SwiftUI, Compose).

## Consequences
- One protocol implementation and one test suite, run on the JVM.
- Apple targets need Xcode and a Mac to build; CI runs on macOS runners.
- The core must stay free of platform imports so it remains portable to iOS.
