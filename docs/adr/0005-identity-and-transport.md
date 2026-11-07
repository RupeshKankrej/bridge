# ADR 0005 — P-256 identity, mutual TLS and a TransportPort

**Status:** accepted · 2026-11-07

## Context
Spec v1.0 §3 asked for an Ed25519 identity with a self-signed TLS certificate derived from it, and §8 listed
libsodium and Ktor (CIO) for crypto and networking. While building M1.2:
- Network.framework TLS needs a `SecIdentity`, and `SecKey`/Keychain support RSA and ECDSA only. Android Keystore
  gained Ed25519 only in API 33, and our minSdk is 29. An Ed25519 key can't be the TLS identity on either platform.
- Ktor on Kotlin/Native has no TLS server, so `core` can't own the control socket on the Mac.
- The Ktor CIO client uses Ktor's own TLS implementation, which supports TLS 1.2 only. Hard rule 5 requires TLS 1.3.
- Apple has no API for creating an X.509 certificate.

## Decision
- The identity is an **ECDSA P-256** key created inside the OS keystore. `core` builds a minimal self-signed X.509 v3
  certificate (`CN=Bridge`, valid from 2025-01-01, notAfter 99991231235959Z, no extensions) and has the keystore
  sign it through `KeyStorePort.sign`. The device ID is the SHA-256 of the DER certificate, lowercase hex. No libsodium.
- A new **`TransportPort`** carries the TLS 1.3 WebSocket: `NWListener` with `NWProtocolTLS` + `NWProtocolWebSocket` on
  macOS, and OkHttp (platform Conscrypt TLS) on Android. Both are **mutual TLS**. The platform accepts any certificate
  and reports its fingerprint; `core` does all trust decisions (QR fingerprint, trusted list, `hello.deviceId` ==
  certificate fingerprint).
- The pairing code is a one-time secret carried in the QR. `pair.request` must present it (proving the scanner saw the
  Mac's screen), and a wrong guess rotates it. The QR fingerprint authenticates the Mac to the tablet.
- Android dependencies added with approval: OkHttp, and `play-services-code-scanner` for the QR scanner (Play services
  draws the UI, so no CAMERA permission). Hilt is deferred until the app has a real dependency graph.
- Trusted peers on Android live in app-private SharedPreferences: Android Keystore stores keys, not data, and
  fingerprints aren't secret.

## Consequences
- Contract tests drive two real `Bridge` instances over an in-memory transport with virtual time. TLS itself is
  covered on the devices (docs/testing.md), not in CI.
- On macOS the certificate DER is stored as a generic-password item, because the file-based Keychain ignores
  `kSecAttrLabel` on certificate items. `SecIdentityCreateWithCertificate` finds the private key by its public key.
- Ad-hoc-signed debug builds of the Mac app change their code signature on every build, so the Keychain may ask
  for permission to use the identity key after a rebuild.
