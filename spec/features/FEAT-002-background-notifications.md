# FEAT-002 — Background Update Notifications

## Goal

Notify users about available app updates via background notifications, without overwhelming them with repeated notifications for the same updates they've already seen.

## Non-Goals

- In-app notification banners (out of scope, UI already shows updates)
- Per-app notification settings (all-or-nothing via Android system settings)
- "Smart" notification timing based on user behavior patterns

## User-Visible Behavior

- User receives a notification when new app updates are available AND they haven't opened the app recently (24+ hours)
- Tapping notification opens the app directly to the Updates screen
- User does NOT receive repeated notifications for updates they've already seen (either via previous notification OR in the app UI)
- Only genuinely new releases (created after user last saw the app) trigger notifications

## Edge Cases

- User opens app, sees updates, doesn't install → no re-notification for those same updates
- User dismisses notification without opening → no re-notification (release timestamp hasn't changed)
- No installed apps → no notifications ever sent
- All updates have release dates before last app open → no notification shown
- New release appears (newer timestamp) → notification sent for that release only

## Acceptance Criteria

- [ ] Background check runs every 24 hours (not 6 hours)
- [ ] Notification only shown if user hasn't opened app in 24+ hours
- [ ] Notification only includes releases with createdAt > seenUntil AND > lastAppOpened
- [ ] Tapping notification navigates to Updates screen
- [ ] seenUntil timestamp updated when notification is shown

## Notes

- Uses `seenUntil` timestamp approach — simpler than tracking individual app IDs
- Filters by both `seenUntil` (last notification time) AND `lastAppOpened` (last time user saw app)
- This prevents nagging about updates user already saw in the app UI but chose to ignore
- The foreground `Timer.periodic` in `updates_service.dart` does NOT run in the background — only WorkManager does
