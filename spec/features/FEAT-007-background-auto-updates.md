# FEAT-007 — Background Auto-Updates

## Goal

Let users opt in to having periodic background update checks download and apply
updates automatically, with a notification that reports what happened instead of
only announcing that updates are available.

## Non-Goals

- Enabling auto-updates by default (toggle is off until the user turns it on)
- Foreground auto-install without user action (FEAT-003; Update All still
  requires an explicit tap while the app is open)
- Per-app auto-update settings (all-or-nothing via the profile toggle)
- Background uninstall or force-update flows
- iOS or non-Android platforms

## User-Visible Behavior

### Profile setting

- **Background auto-updates** toggle in Profile → Data Management
- Default: **off**
- Subtitle explains that background checks download and apply updates; manual
  updates are downloaded and shown as ready to install

### When toggle is OFF

- FEAT-002 behavior is unchanged: background checks may notify about available
  updates (subject to the 24h inactivity and freshness rules), and tapping opens
  the Updates screen

### When toggle is ON

- Periodic WorkManager checks (same 24h schedule as FEAT-002) **perform** update
  work instead of only notifying about availability
- Automatic APK downloads and installs run only on an unmetered Wi-Fi
  connection; cellular and other metered connections may check for update
  availability but must not download APKs
- The 24h inactivity gate does **not** block performing updates; the user opted in
- For each available update (same catalog / versionCode rules as the Updates screen):
  - **Silent updates** (app was installed by Zapstore and supports silent
    install): download, verify hash, install in background without user interaction
  - **Manual updates**: download, verify hash, stage as **ready to update**; do
    **not** show the Android install dialog until the user acts
- After a check that did work, user receives a **result notification** summarizing:
  - Apps successfully updated
  - Apps ready to update (with “tap to install” guidance)
  - Apps that failed to update
- If there is nothing to update, or no work was performed, no result notification
- Tapping a result notification that includes ready-to-update apps:
  - Opens the app
  - Triggers the Android system install prompt for staged manual updates
  - Navigates to the Updates screen
- Tapping a result-only notification (updates applied, no manual pending):
  navigates to the Updates screen

### Security

- APKs are verified (hash, and signing metadata when declared) before staging or
  installing — same guarantees as FEAT-001
- Silent background install only for apps that already qualify for silent install
  in the foreground Updates flow

## Edge Cases

- Toggle turned off after manual updates were staged → staged files remain until
  used, cleared, or aged out; no new background apply runs
- Toggle turned on with no network → check fails; WorkManager retries per existing
  backoff policy; no silent failure
- Toggle turned on while only a cellular or metered connection is available →
  update availability may be checked, but APK download and installation wait for
  unmetered Wi-Fi
- Download fails → app listed under failures in result notification; no partial
  install
- Hash or certificate verification fails → staged file deleted; app listed as failed
- Silent install fails in background → app listed as failed; no install dialog
- Manual update staged but user never taps notification → APK remains in pending
  store; user can still update from the Updates screen if the file is present
- Multiple manual updates ready → notification tap starts install flow; Android
  shows one system prompt at a time (FEAT-001 install queue semantics)
- No installed apps → no background update work, no notification
- Device policy blocks installs → failure surfaced in result notification
- User lacks “install unknown apps” permission → manual staging may succeed but
  install prompt on tap follows FEAT-001 permission flow

## Acceptance Criteria

- [ ] Profile toggle **Background auto-updates** exists and defaults to off
- [ ] When off, FEAT-002 notification behavior is unchanged
- [ ] When on, background checks download and apply silent updates without user
      interaction, only while connected to unmetered Wi-Fi
- [ ] When on, manual updates are downloaded and reported as ready to update,
      without showing the install dialog until the user taps the notification;
      background download occurs only on unmetered Wi-Fi
- [ ] When on, result notification summarizes updated / ready / failed outcomes
- [ ] Tapping a ready-to-update notification triggers the Android install prompt
- [ ] APK hash is verified before any background install or staging
- [ ] Update availability still uses versionCode comparison only (INVARIANTS)

## Notes

- Builds on FEAT-002 (WorkManager schedule) and FEAT-001 (install state machine,
  silent vs manual categorization)
- FEAT-003 non-goal “auto-install without user action” applies to foreground
  behavior; this feature is an explicit opt-in for background apply only
- Setting key: `backgroundAutoUpdatesEnabled` in `LocalSettings`
- Implementation: `spec/work/WORK-013-background-auto-updates.md`
