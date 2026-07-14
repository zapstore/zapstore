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
- [x] Confirm opt-in before enabling and explain first-run timing and cadence
- [x] Queue the first auto-update run immediately with an unmetered-network constraint
- [x] Emulator UAT: schedule constraints, staging, notification, and install prompt
- [x] Refresh foreground package and Updates-screen state after a headless silent update
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
- The first opt-in run is a one-off WorkManager task constrained to an
  unmetered network. It runs as soon as Wi-Fi is available and does not poll
  while offline; the existing periodic auto-update task remains approximately
  every 24 hours.
- A headless worker emits a completion-only native event to foreground Flutter
  engines after silent updates succeed. Foreground state then rescans installed
  packages and refreshes local catalog categories; install-progress events are
  never replayed into the foreground state machine.

## Test Coverage

| Scenario | Expected | Status |
|----------|----------|--------|
| Enable and confirm | Setting is saved and first unmetered run is queued | [ ] |
| Enable and cancel | Dialog closes and setting remains off | [ ] |
| Enable without Wi-Fi | First run stays deferred until the network constraint is met | [ ] |
| Disable | No new auto-update work is scheduled | [ ] |
