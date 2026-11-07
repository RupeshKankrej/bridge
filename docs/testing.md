# Testing

## Automated
- `./gradlew :core:allTests` — codec round-trips, session state machine, pairing, LocalSend parsing, contract tests over localhost. Run before every commit.
- `./gradlew :core:localSendInteropTest` — exchanges files with the official LocalSend build in both directions. Needs LocalSend CLI on PATH.

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

## One-time device checks
- [ ] Android version reported by the Pad 3 in Settings → About
- [ ] `packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)` on the Pad 3 (needed for Tier B AirDrop)
- [ ] `Presentation` display support on the Pad 3 (needed for F10)
