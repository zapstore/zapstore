# WORK-013 — Background Auto-Updates

**Feature spec:** `spec/features/FEAT-007-background-auto-updates.md`

## Goal

Profile toggle (off by default) that makes background update checks download and
apply updates, with a result notification instead of "updates available".

## Tasks

- [x] Add `backgroundAutoUpdatesEnabled` to LocalSettings (default false)
- [x] Profile screen toggle
- [x] Background executor: download, silent install, stage manual installs
- [x] Native `installAndAwait` + `verifyApk` for background isolate
- [x] Pending manual install store + notification tap → system prompt
- [x] Result notification when auto-updates enabled
- [x] Restrict background APK downloads and installs to unmetered networks
- [x] Refresh installed package versions natively before each background run
- [x] Reuse already-staged manual updates instead of downloading them again
- [x] Register the native package manager in headless WorkManager engines
- [x] Emulator UAT: schedule constraints, staging, notification, and install prompt
- [ ] Manual UAT on device

## Decisions

- When enabled, skip the 24h inactivity gate for performing work; the user opted in.
- When disabled, keep FEAT-002 notification behavior unchanged.
- Manual updates: download + verify in background; install dialog only on notification tap.
- Silent updates: full install in background via `installAndAwait`.
- Keep notification-only checks on any connected network; use a separate
  unmetered WorkManager task for auto-update downloads and installs.
- Fall back to the local installed-package snapshot only when the native
  background package scan fails.
- Certificate metadata extraction must succeed when certificate hashes are
  declared; otherwise staging fails closed.
- Use a generated plugin registrant bridge so activity and headless Flutter
  engines share the same app-owned Android package manager implementation.
