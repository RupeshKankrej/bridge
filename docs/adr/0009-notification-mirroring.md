# ADR 0009 — Notification mirroring: message shape, icon frames and muting

**Status:** accepted · 2026-11-07

## Context
M2.1 mirrors tablet notifications to the Mac (spec §5.4) with actions, inline reply and dismiss sync, and asks for a
global mute while the Mac is in Do Not Disturb or presenting. The icon travels as a WebSocket binary frame (spec §4),
which `TransportPort` couldn't carry until now.

## Decision
- `Connection.sendBinary` / `ConnectionListener.onBinary`. Core pairs a binary frame with the message right before it
  that set `binaryLength`; a text frame in between is an `invalid_message` error and the announced message is dropped.
  Binary frames are capped at 64 KiB (`MAX_ICON_BYTES`, `too_large`). A binary frame with nothing waiting for it (e.g.
  following a newer peer's unknown type, already answered with `unsupported_type`) is ignored. Builds before M2.1 already
  ignore binary frames on both platforms, so the change is backward-compatible; no protocol version bump.
- `notif.posted.actions` lists only the actions *without* a RemoteInput; `notif.action.actionIndex` indexes that list.
  `replyable` says the notification has a free-form RemoteInput action, which `notif.reply` fills. The tablet keeps the
  `Notification.Action`s per key, so nothing executable crosses the wire.
- The icon is the app's launcher icon at 32 px (spec §5.4). On the Mac it can only be the attachment thumbnail: a
  `UNUserNotificationCenter` notification always shows the posting app's (Bridge's) icon.
- One `NotificationPort` with both halves (tablet: watch/remove/action/reply; Mac: watch/show/remove). Local events go to
  every online paired peer; nothing is queued for offline peers, and nothing is re-sent on reconnect.
- **Mute is the OS's.** Focus / Do Not Disturb silences banners from apps it doesn't allow, and "Allow notifications when
  mirroring or sharing the display" (System Settings → Notifications) is off by default, so `UNUserNotificationCenter`
  already mutes Bridge in both cases; muted notifications still land in Notification Center. Detecting Focus ourselves
  would need `INFocusStatusCenter` (an entitlement and a permission prompt) and there's no public "presenting" signal.
- Tablet filters: mirroring is off by default (spec §7) and needs notification access; ongoing notifications, group
  summaries, Bridge's own and blocked apps are skipped; a repost with unchanged title and text is skipped; a repost whose
  text equals the reply we just injected is skipped (apps echo the reply into the notification).
- Dismiss sync: Mac `customDismissAction` → `notif.dismissed` → `cancelNotification(key)`; the tablet's
  `onNotificationRemoved` sends `notif.dismissed` for anything it forwarded, except removals it made itself
  (`REASON_LISTENER_CANCEL`), so a dismissal never echoes.

## Consequences
- A failed action or reply on the tablet (notification gone, PendingIntent cancelled) is logged there but not reported to
  the Mac. Actions that start an activity may be blocked by Android's background-activity-start rules.
- If a user allows Bridge through a Focus, it isn't muted there; that's the user's choice.
