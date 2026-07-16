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
