# ADR 0012 — Tablet redesign, settings screen, diagnostics and local crash log (M2.4, M2.5)

**Status:** accepted · 2026-11-07

## Context
M2.4 asks for AirDrop visibility, receive-folder and transfer-list plumbing; M2.5 for two weeks of daily use. Rupesh asked
alongside M2.5 for a diagnostics screen on both apps, crash reporting to a local file, and a tablet UI that looks like a
current OnePlus / Material You app with all toggles in Settings and LocalSend-style sending by type. Spec §7 had put the
per-feature toggles on the tablet's home screen.

## Decision
- Tablet: Material 3 with dynamic colour, a navigation rail Home / Send / Settings. Feature switches move to Settings
  (spec §7 changed). Icons are Material Symbols copied as vector drawables: no `material-icons-extended` dependency.
  No navigation library: the three tabs and three sub-screens are plain Compose state.
- Send tab like LocalSend: File, Media (system photo picker, no storage permission), Text, Folder, Paste. Text uses
  LocalSend's text message (`preview` on the file DTO, new optional `OutgoingFile.preview` in core); LocalSend shows it
  as a message, a Bridge receiver saves the `.txt`. No wire or LocalSend API change.
- AirDrop visibility (M2.4): Off / Bridge devices only / Everyone for 10 minutes with "Keep Everyone on", in Settings and
  a quick-settings tile. Stored only; nothing advertises until Tier A or B, and the UI says so. The Mac menu-bar control
  would need a new wire message for a setting that does nothing yet, so it moves to Tier B (spec §5.10, §7 changed).
- Receive folder (M2.4): Mac picks any folder (not sandboxed, so a path in UserDefaults). Tablet picks a SAF tree with a
  persisted write grant; files are created through `DocumentsContract`, which has no IS_PENDING, so a partly received
  file is visible under its final name until done (deleted on failure). Default stays `Download/Bridge` via MediaStore.
- Transfer list plumbing (M2.4): the existing list is shown on Home and the Mac popover; AirDrop transfers join it with Tier B.
- Diagnostics: core adds `PeerStatus.address/connectedAt/rttMs` and `BridgeState.lastError/lastErrorAt`, and
  `Connection.remoteAddress`. Link quality is the round trip of the existing 15 s heartbeat ping (plus one right after a
  session pairs), thresholds in core (`linkQuality`). No new message.
- Crash log: Android's default uncaught-exception handler appends to `files/crash.log` (256 KB cap) and passes on.
  Mac: `NSSetUncaughtExceptionHandler`, `NSApplicationCrashOnExceptions`, and Kotlin/Native's unhandled-exception hook
  (`installCrashHook` in appleMain) append to `~/Library/Logs/Bridge/crash.log`. Swift traps are left to macOS's own
  DiagnosticReports, linked from the diagnostics view. Nothing is uploaded; copying the log is a user action.

## Consequences
- Two Swift classes must implement the new port members (`remoteAddress`, `preview`): Kotlin interface defaults don't
  reach Objective-C protocols.
- The diagnostics' round trip refreshes every 15 s; good enough for a health view, not a latency graph.
