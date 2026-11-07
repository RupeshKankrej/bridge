# ADR 0008 — Reconnect triggers and how the tablet finds the Mac's address

**Status:** accepted · 2026-11-07

## Context
M1.5 asks the session to survive sleep/wake and Wi-Fi switches, and to "fall back to the last known IP for 30 s when
mDNS is slow". Until M1.5 the tablet dialled only the address from the QR code, retried with a backoff of up to 30 s,
and noticed a dead socket only after three missed pings (~60 s). The Mac never dials; it only accepts.

## Decision
- `Bridge.reconnect()` in `core`: offline paired peers are dialled at once (backoff reset), and every online session is
  pinged; one that doesn't answer within 5 s is dropped, so a socket from before sleep doesn't hold the slot for a minute.
  Called on Mac wake (`NSWorkspace.didWakeNotification`) and network-path change (`NWPathMonitor`), and on the tablet on
  unlock (`ACTION_USER_PRESENT`, registered by the service) and default-network change (`ConnectivityManager`).
- The tablet browses `_bridge._tcp` with NsdManager and resolves IPv4 addresses. Each dial attempt tries the mDNS address
  first, then the stored one. **The last known IP is kept indefinitely, not for 30 s:** on networks that block multicast,
  it (or a typed address) is the only way back, and dropping it after 30 s would leave nothing to dial. A new mDNS
  address for an offline peer triggers an immediate dial.
- mDNS answers are unauthenticated. An address is saved to the trust store only after a TLS handshake to it presents the
  pinned certificate, so a spoofed announcement can cost a failed dial but can't overwrite the real address.
- Manual address (client-isolated or mDNS-blocked Wi-Fi): an optional field on the tablet's pairing screen replaces the
  QR's host; the fingerprint and code still come from the QR, so pairing security is unchanged. An offline paired Mac's
  address can be edited the same way (`Bridge.setAddress`).
- Android keeps running through: the existing `connectedDevice` foreground service, a battery-optimisation exemption
  requested on the home screen until granted, and a 15-minute WorkManager job that restarts the service (WorkManager's
  minimum period; it also survives reboots). Android 12+ allows that background start only while the app is exempt.
- Mac: `SMAppService.mainApp` login item, registered once on first run (spec §7 default On), with a menu toggle.

## Consequences
- No wire-protocol change: the probe is an ordinary `ping`.
- Two new Android install-time permissions: `ACCESS_NETWORK_STATE`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
  New dependency `androidx.work:work-runtime` (WorkManager, listed in spec §8).
- No boot receiver: after a reboot Bridge returns within 15 minutes via WorkManager, or when opened.
- If the Mac's address changes on a network without mDNS, the user types the new one on the tablet.
