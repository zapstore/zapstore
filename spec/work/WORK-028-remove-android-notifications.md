# WORK-028 — Remove Android System Notifications

**Feature:** FEAT-002-background-notifications.md, FEAT-007-background-auto-updates.md
**Status:** Complete

## Tasks

- [x] 1. Remove `POST_NOTIFICATIONS` from AndroidManifest
  - Files: `android/app/src/main/AndroidManifest.xml`
- [x] 2. Remove runtime notification permission request and local-notification plumbing
  - Files: `lib/services/background_update_service.dart`
- [x] 3. Drop unused packages and notification icons
  - Files: `pubspec.yaml`, `android/app/src/main/res/drawable*/ic_notification.xml`
- [x] 4. Stop registering the notification-only background check worker
  - Notes: With no notifications, the FEAT-002 worker has no user-visible effect
- [x] 5. Self-review against INVARIANTS.md

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| Cold start (release) | No Android notification permission dialog | [ ] |
| Background auto-updates ON | Silent installs still run; no result notification | [ ] |
| Background auto-updates OFF | No availability notification | [ ] |

## Decisions

### 2026-07-17 — Remove system notifications entirely

**Context:** Product request to remove the Android “Allow notifications?” prompt.
**Options:** (A) Stop requesting only, keep permission + show paths; (B) Remove permission + request + show paths.
**Decision:** B — remove both the runtime request and `POST_NOTIFICATIONS`, and delete show/tap plumbing so failures are not silent.
**Rationale:** Without the permission, posts cannot succeed on Android 13+; leaving show paths would violate UX Safety (silent failures).

## Spec Issues

- **Issue:** FEAT-002 and FEAT-007 require system notifications (availability alerts, result summaries, tap-to-install for staged manual updates).
- **Question:** Product chose removal anyway. Feature specs remain human-owned and still describe the old behavior until updated.
- **Mitigation in code:** Keep WorkManager auto-update execution and pending-install staging; drop notification-only checks and all `flutter_local_notifications` usage. Staged manual updates remain on disk for the Updates screen / next user-driven install (per FEAT-007 edge case when notification is never tapped).

## Progress Notes

**2026-07-17:** Removed `POST_NOTIFICATIONS`, runtime request, `flutter_local_notifications` / `permission_handler`, notification show/tap paths, and notification-only WorkManager registration. Background auto-update execution kept.
