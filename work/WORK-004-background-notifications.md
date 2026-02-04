# WORK-004 — Background Notifications

**Feature:** FEAT-002-background-notifications.md
**Status:** Complete

## Tasks

- [x] 1. Add secure storage methods for notification state tracking
  - Files: `lib/services/secure_storage_service.dart`
  - Add `getLastAppOpenedTime()` / `setLastAppOpenedTime()`
  - Add `getSeenUpdateIds()` / `setSeenUpdateIds()` / `clearSeenUpdateIds()`

- [x] 2. Record "last app opened" time when app resumes
  - Files: `lib/main.dart`
  - Update `_AppLifecycleObserver.didChangeAppLifecycleState()` to record timestamp on resume
  - Also record on initial launch (in `appInitializationProvider`)

- [x] 3. Mark updates as "seen" when user opens app
  - Files: `lib/main.dart`
  - On app open, clear seen update IDs (so we can track new ones)
  - Cleared in `_recordAppOpened()` and during initial launch

- [x] 4. Change background check frequency to 24 hours
  - Files: `lib/services/background_update_service.dart`
  - Changed `frequency: Duration(hours: 6)` to `Duration(hours: 24)`
  - Updated `initialDelay` from 15 minutes to 1 hour

- [x] 5. Implement smart notification logic
  - Files: `lib/services/background_update_service.dart`
  - Check "last app opened" time — skip if < 24 hours ago
  - Filter updates to only those not in "seen" list
  - Only notify if filtered list is non-empty
  - Removed old 72-hour throttle logic

- [x] 6. Configure notification tap to navigate to Updates screen
  - Files: `lib/services/background_update_service.dart`
  - Pass payload with notification (`_kNotificationPayload`)
  - Handle `onDidReceiveNotificationResponse` via `_handleNotificationTap`
  - Handle launch from terminated state via `getNotificationAppLaunchDetails()`

- [x] 7. Self-review against INVARIANTS.md
  - ✅ UI Safety: All operations are async, no UI blocking
  - ✅ Async Discipline: WorkManager is designed for periodic background work (same note as WORK-003)
  - ✅ Local-First: Notifications enhance UX, don't gate functionality
  - ✅ Lifecycle Safety: No resource leaks, all async
  - ✅ UX Safety: Notifications provide clear information

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| App not opened in 24h, new updates exist | Notification shown | [ ] |
| App opened recently (< 24h), updates exist | No notification | [ ] |
| App not opened in 24h, but updates already seen | No notification | [ ] |
| Tap notification | Opens to Updates screen | [ ] |
| Dismiss notification, next cycle | May re-notify if still inactive | [ ] |
| Open app after notification | Updates marked as seen | [ ] |

## Decisions

### 2026-02-04 — "Seen" tracking on app open, not notification show

**Context:** How to track which updates user has been notified about without re-notifying for the same updates.
**Options:** (A) Mark seen when notification shown, (B) Mark seen when app opened.
**Decision:** Option B — mark seen when app opened.
**Rationale:** If user dismisses notification without opening, we want the option to re-notify. Marking on notification show would prevent re-notification even when user hasn't seen updates.

### 2026-02-04 — 24-hour check frequency

**Context:** How often should background checks run?
**Options:** (A) Keep 6h checks, (B) Change to 24h, (C) 12h middle ground.
**Decision:** Option B — 24 hours.
**Rationale:** Since we only notify users who haven't opened app in 24+ hours, checking more frequently adds battery cost with no benefit. When user opens app, the foreground `Timer.periodic` handles immediate updates.

### 2026-02-04 — Simplify "seen" tracking with timestamp (REVISED)

**Context:** Original design stored `Set<String>` of seen app IDs, cleared on app open. This had a flaw: clearing on app open means the next background check (24h later) would re-notify about the same updates the user already saw and chose to ignore.

**Options considered:**
- (A) Store `Set<appId>` — doesn't handle new versions of same app
- (B) Store `Set<appId:versionCode>` — handles new versions but unbounded growth
- (C) Store single `seenUntil` timestamp — simple, compare against release.createdAt

**Decision:** Option C — single `seenUntil` timestamp.

**Rationale:**
- Much simpler: one timestamp vs unbounded set
- No clearing needed on app open
- Filter: only notify about releases where `release.createdAt > seenUntil`
- When notification shown, set `seenUntil = now()`
- New releases (even for same app) have newer timestamps, so they'll trigger
- Old ignored updates won't re-notify (their timestamps stay old)

## Files Modified

| File | Change |
|------|--------|
| `lib/services/secure_storage_service.dart` | Add last app opened time and seen update IDs storage |
| `lib/main.dart` | Record app open time, mark updates as seen on launch |
| `lib/services/background_update_service.dart` | 24h frequency, smart notification logic, deep link to updates |

## Spec Issues

_None_

## Refactor Tasks (seenUntil timestamp approach)

- [x] 8. Replace seenUpdateIds with seenUntil timestamp in secure storage
  - Files: `lib/services/secure_storage_service.dart`
  - Removed `getSeenUpdateIds()` / `setSeenUpdateIds()` / `clearSeenUpdateIds()`
  - Added `getSeenUntil()` / `setSeenUntil()`

- [x] 9. Remove clearSeenUpdateIds calls from main.dart
  - Files: `lib/main.dart`
  - Removed from `_recordAppOpened()` and `appInitializationProvider`
  - Kept `setLastAppOpenedTime()` (still needed for 24h inactivity check)

- [x] 10. Update background notification logic to use seenUntil
  - Files: `lib/services/background_update_service.dart`
  - Filter: `release.createdAt > seenUntil AND > lastOpened`
  - On notification shown: `setSeenUntil(now())`
  - Handles null seenUntil gracefully (first run uses lastOpened as fallback)

## Progress Notes

**2026-02-04:** Design finalized. Starting implementation.
**2026-02-04:** Initial implementation complete.
- Replaced 72-hour notification throttle with smart logic based on app activity
- User must be inactive 24+ hours AND have unseen updates to receive notification
- Notification tap navigates directly to Updates screen
- Background check frequency changed from 6 hours to 24 hours to match inactivity threshold

**2026-02-04:** Design revision — seenUpdateIds approach had flaw.
- Problem: Clearing seenIds on app open meant user would be re-notified about same ignored updates
- Solution: Replace with single `seenUntil` timestamp
- Filter by `release.createdAt > seenUntil` instead of checking set membership
- Simpler, no unbounded storage growth, handles new versions naturally

**2026-02-04:** Refactor complete.
- Replaced seenUpdateIds with seenUntil timestamp
- Added extra filter: `release.createdAt > lastOpened` to prevent nagging about updates user saw in app UI
- Final logic: only notify if release is newer than BOTH last notification AND last app open
- This ensures user won't be nagged about updates they already saw and chose to ignore
