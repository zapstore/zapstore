# WORK-026 - New Device Key Reminder

**Feature:** FEAT-006-device-key.md
**Status:** In Progress

## Tasks

- [x] 1. Detect a missing device key before generating the fresh key
  - Files: `lib/main.dart`, `lib/services/device_key_service.dart`
  - Keep the result in memory for the current app session only.
- [x] 2. Replace the startup restore dialog with an inline reminder
  - Files: `lib/screens/search_screen.dart`, `lib/services/device_backup_service.dart`
  - Render the reminder between search and stacks only for a newly generated key.
- [x] 3. Support restoring a pasted nsec from Device key management
  - Files: `lib/screens/profile_screen.dart`, `lib/widgets/device_restore_dialog.dart`
    `lib/services/device_backup_service.dart`
  - Validate, replace, and sync a pasted-nsec or Amber-restored key without
    blocking the UI.
- [x] 4. Verify analysis and targeted tests
  - `fvm flutter test test/services/device_key_service_test.dart
    test/services/device_backup_service_test.dart
    test/services/settings_service_test.dart`
  - `HOME=/tmp fvm flutter analyze`
- [x] 5. Protect Amber recovery records
  - Files: `lib/services/device_backup_service.dart`,
    `lib/widgets/device_restore_dialog.dart`
  - Normal Amber sign-in preserves an existing verified Amber recovery record;
    explicit Device key management offers Amber recovery without scheduling a
    replacement backup.

## Verification

- The startup flow captures `device_key` presence before generating the key;
  only an absent key enables the session-only reminder.
- Pasting an invalid nsec surfaces an error without replacing the current key.
- A successful restore replaces the device key before the one-shot private-data
  synchronization begins.

## Decisions

### 2026-07-15 - New-key reminder is session-only

**Context:** A first-run restore dialog blocks discovery and requires users to
choose a recovery method before using the store.

**Decision:** Capture whether `device_key` was absent before creating it, then
show a non-interactive reminder for the current app session. The key's presence
suppresses it on later launches; no onboarding state is written.

**Rationale:** Device-key recovery remains accessible from Data Management at
any time without treating Amber as a prerequisite or gating the UI.

The obsolete persisted `restoreOnboardingComplete` value is removed. Amber
recovery is now explicitly initiated from Device key management, which prevents
normal Amber sign-in backup from overwriting a recovery record first.

### 2026-07-17 - Existing Amber recovery records are immutable on sign-in

**Context:** A fresh install creates a new device key before the user can sign
in with Amber. Automatically backing up that key could replace the only record
that points to the prior device's private state.

**Decision:** On a normal Amber sign-in, decrypt any existing Amber backup. If
it holds a different device key, offer restore (never overwrite). If it matches
the current key, do nothing. Only create a new Amber backup when none exists.

**Rationale:** Sign-in is the path users expect for recovery. Preserving the
remote backup alone left them with an empty fresh key and no prompt.

### 2026-07-17 - Emulator verification

Verified on `emulator-5554` with a fresh Amber account:

1. Signed in, enabled background auto-updates, confirmed device-state publish.
2. Cleared Zapstore data (new device key generated).
3. Normal Amber sign-in logged `preserving existing Amber device-key backup`
   and left the new key in place.
4. Profile → Restore → Restore with Amber restored `npub15adrzvs…`.
5. After relaunch, background auto-updates was on again.
6. Restored portable settings now also invalidate `localSettingsProvider` so
   the toggle refreshes without requiring an app restart.

### 2026-07-17 - Private stack hydration after restore

**Context:** `syncRestoredKey` did query kinds `30267`/`30078`, but discarded the
result and relied on isolate `QueryResultNotification` to refresh LocalSource
stack watchers. After a pubkey swap those watchers can miss the update, and
bookmarks/unmanaged notifiers kept empty in-memory state from the fresh key.

**Decision:** Sync with `LocalAndRemoteSource`, re-save fetched models so
LocalSource consumers refresh with `req:null`, and reset bookmarks/unmanaged
notifiers whenever `devicePubkeyProvider` changes.

**Rationale:** Private stacks must decrypt and appear under the restored device
key without requiring an app restart.
