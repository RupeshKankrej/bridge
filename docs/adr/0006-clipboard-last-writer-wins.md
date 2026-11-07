# ADR 0006 — Clipboard last-writer-wins and catch-up on reconnect

**Status:** accepted · 2026-11-07

## Context
Spec §5.2 says clipboard conflicts resolve by `ts`, newest wins, and received items carry an origin tag so they
don't echo. Building M1.3 raised four gaps:
- The envelope `ts` was "sender's epoch millis". A resend of an older item would then look new and win.
- Two copies in the same millisecond on both devices: each side would keep its own (or both would swap).
- Clocks on the Mac and tablet differ. If the peer's clock is ahead, the user's latest real copy could lose.
- `clipboard.request` had no defined trigger.

## Decision
- For `clipboard.set`, `ts` is the copy time; replies to `clipboard.request` keep the original `ts`.
- Ordering is `(ts, copying device's ID)`, compared the same way on both sides.
- A local copy gets `ts = max(now, newest known ts + 1)`: a real copy is always the newest event.
- Each side sends `clipboard.request` after every (re)connect, so copies made while apart converge.
- `core` holds the shared item in memory only (never on disk, spec §3). At launch it starts from a contentless
  floor at the launch time, so a peer's item from before our launch never overwrites the OS clipboard, whose age
  we can't know.
- Origin tag is platform-side (`dev.bridge.origin` pasteboard type / clip label). `core` also drops a local copy
  equal to the shared text, so a platform that loses the tag still can't echo.
- Not the `not_paired` error code for clipboard messages on an unpaired session (that code makes the peer forget
  us); `unauthorized` instead.

## Consequences
- A copy made on one device while the other's app was not running is not delivered when it starts. Acceptable for
  Phase 1; revisit if it bites in daily use (M2.5).
- The Mac polls every 250 ms (spec §5.2), so Mac → tablet is up to 250 ms + network after the copy. The
  "< 150 ms" budget (spec §8) holds for propagation after the copy is detected, not end-to-end. Lowering the poll
  interval to ~50 ms would meet it end-to-end at a small CPU cost; not done without agreement.
