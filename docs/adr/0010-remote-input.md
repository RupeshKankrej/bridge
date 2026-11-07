# ADR 0010 — Remote input: wire units, coalescing, pinch and Accessibility

**Status:** accepted · 2026-11-07

## Context
M2.2 turns the tablet into a trackpad and keyboard for the Mac (spec §5.5). Spec §4 named the `input.pointer` and
`input.key` fields but not their units, and asked for 120 events/s with coalescing of pointer deltas.

## Decision
- **Units.** `dx`/`dy` are Mac points; the tablet applies pointer acceleration, so the Mac just adds them to the cursor
  position. `scrollX`/`scrollY` are pixels in the direction the content moves (the fingers' direction), so scrolling is
  always "natural" like a touch screen; the Mac's Natural scrolling setting doesn't apply to injected events anyway.
- **Buttons are state, not clicks.** `buttons` is the held set (1 left, 2 right). The Mac moves, then presses/releases what
  differs from the previous message. A click is two messages; this also allows drags later without a protocol change.
  The Mac sets the click count itself (double-click interval, < 5 pt apart) so double-click works.
- **Key codes are macOS virtual key codes** (`kVK_*`, US ANSI positions; `MacKey` in core). The Mac is the only receiver
  (iOS can't inject), so a neutral code set would just add a second table. `text` carries IME output and is injected as
  Unicode strings, which works whatever the Mac's keyboard layout; `keyCode` is for keys the text can't express (arrows,
  Return, Delete) and for shortcuts, which need a key code plus modifiers. Exactly one of the two is set.
- **Rate limit and coalescing live in the sender's core** (`Bridge.pointer/buttons/zoom/key/type`). One queue, one message
  per 1/120 s, the first one immediately. Motion is merged into the queue's tail while that tail is an unsent motion
  message; clicks, keys and gestures are appended in order and never merged, so a fast tap can't lose its release. Nothing
  is queued for an offline Mac. The receiver doesn't re-limit: the sender is a paired device.
- **Pinch = ⌘= / ⌘−**, one step per 1.25× change of the finger span. macOS has no public API to post magnify gestures;
  the undocumented CGEvent gesture fields would fall under hard rule 7. ⌘=/⌘− zooms in browsers, Preview, Maps, Xcode and
  most document apps; apps that only zoom by pinch (Photos) won't.
- **Accessibility.** The Mac checks `CGPreflightPostEventAccess` before injecting (cached once granted, re-checked at most
  once a second while missing). On the first refusal it shows an alert explaining why, whose button calls
  `CGRequestPostEventAccess` (adds Bridge to the list) and opens the Accessibility pane. The port returns false, and core
  answers the first refused event of a run with `error` code `permission_missing`, which the tablet shows.

- **Latency on the tablet.** OkHttp leaves Nagle's algorithm on, which held each small frame until the previous one was
  ACKed; the control socket now sets `TCP_NODELAY`. The trackpad screen holds a `WIFI_MODE_FULL_LOW_LATENCY` lock
  (Wi-Fi power save off; Android grants it only to the foreground app with the screen on). Measured on the Pad 3:
  pings to the router went from 23 ms average / 217 ms worst to 10 ms / 43 ms.

## Consequences
- On the Mac, AWDL (the radio behind AirDrop, Handoff, Universal Control, Sidecar) hops channels and added most of the
  remaining spikes: tablet ↔ Mac pings went from 71 ms average / 600 ms worst to 25 ms / 250 ms with `awdl0` down. There
  is no public API to pause it, so Bridge doesn't; the rest of the jitter is the Wi-Fi network itself.
- Pointer feel (gain, acceleration, tap time, scroll gain) are constants in `TrackpadUi.kt`, tuned on the Pad 3.
- No momentum scrolling, tap-and-drag, three-finger gestures, held modifiers (⌘-Tab switching needs a held ⌘) or
  hardware-keyboard forwarding yet.
- The tablet's error message stays until the next reconnect even after Accessibility is granted.
- Dev builds re-signed differently lose the Accessibility grant silently; docs/testing.md says to re-add Bridge.
